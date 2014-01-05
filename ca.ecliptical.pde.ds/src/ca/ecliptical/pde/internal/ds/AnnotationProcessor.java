/*******************************************************************************
 * Copyright (c) 2012, 2014 Ecliptical Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Ecliptical Software Inc. - initial API and implementation
 *******************************************************************************/
package ca.ecliptical.pde.internal.ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.BuildContext;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jface.text.Document;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.internal.core.project.PDEProject;
import org.eclipse.pde.internal.ds.core.IDSComponent;
import org.eclipse.pde.internal.ds.core.IDSConstants;
import org.eclipse.pde.internal.ds.core.IDSDocumentFactory;
import org.eclipse.pde.internal.ds.core.IDSImplementation;
import org.eclipse.pde.internal.ds.core.IDSModel;
import org.eclipse.pde.internal.ds.core.IDSProperties;
import org.eclipse.pde.internal.ds.core.IDSProperty;
import org.eclipse.pde.internal.ds.core.IDSProvide;
import org.eclipse.pde.internal.ds.core.IDSReference;
import org.eclipse.pde.internal.ds.core.IDSService;
import org.eclipse.pde.internal.ds.core.text.DSModel;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

@SuppressWarnings("restriction")
public class AnnotationProcessor extends ASTRequestor {

	private final Map<ICompilationUnit, Collection<IDSModel>> models;

	private final Map<ICompilationUnit, BuildContext> fileMap;

	private final ValidationErrorLevel errorLevel;

	public AnnotationProcessor(Map<ICompilationUnit, Collection<IDSModel>> models, Map<ICompilationUnit, BuildContext> fileMap, ValidationErrorLevel errorLevel) {
		this.models = models;
		this.fileMap = fileMap;
		this.errorLevel = errorLevel;
	}

	@Override
	public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
		HashSet<IDSModel> modelSet = new HashSet<IDSModel>();
		models.put(source, modelSet);
		HashSet<DSAnnotationProblem> problems = new HashSet<DSAnnotationProblem>();

		ast.accept(new AnnotationVisitor(modelSet, errorLevel, problems));

		if (!problems.isEmpty()) {
			char[] filename = source.getResource().getFullPath().toString().toCharArray();
			for (DSAnnotationProblem problem : problems) {
				problem.setOriginatingFileName(filename);
				if (problem.getSourceStart() >= 0)
					problem.setSourceLineNumber(ast.getLineNumber(problem.getSourceStart()));
			}

			BuildContext buildContext = fileMap.get(source);
			if (buildContext != null)
				buildContext.recordNewProblems(problems.toArray(new CategorizedProblem[problems.size()]));
		}
	}
}

@SuppressWarnings("restriction")
class AnnotationVisitor extends ASTVisitor {

	private static final String COMPONENT_ANNOTATION = DSAnnotationCompilationParticipant.COMPONENT_ANNOTATION;

	private static final String ACTIVATE_ANNOTATION = Activate.class.getName();

	private static final String MODIFIED_ANNOTATION = Modified.class.getName();

	private static final String DEACTIVATE_ANNOTATION = Deactivate.class.getName();

	private static final String REFERENCE_ANNOTATION = Reference.class.getName();

	private static final Pattern PID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)*"); //$NON-NLS-1$

	private static final Set<String> PROPERTY_TYPES = Collections.unmodifiableSet(
			new HashSet<String>(
					Arrays.asList(
							null,
							String.class.getSimpleName(),
							Long.class.getSimpleName(),
							Double.class.getSimpleName(),
							Float.class.getSimpleName(),
							Integer.class.getSimpleName(),
							Byte.class.getSimpleName(),
							Character.class.getSimpleName(),
							Boolean.class.getSimpleName(),
							Short.class.getSimpleName())));

	private static final Comparator<IDSReference> REF_NAME_COMPARATOR = new Comparator<IDSReference>() {

		public int compare(IDSReference o1, IDSReference o2) {
			return o1.getReferenceName().compareTo(o2.getReferenceName());
		}
	};

	private static final Debug debug = Debug.getDebug("ds-annotation-builder/processor"); //$NON-NLS-1$

	private final Collection<IDSModel> models;

	private final ValidationErrorLevel errorLevel;

	private final Set<DSAnnotationProblem> problems;

	public AnnotationVisitor(Collection<IDSModel> models, ValidationErrorLevel errorLevel, Set<DSAnnotationProblem> problems) {
		this.models = models;
		this.errorLevel = errorLevel;
		this.problems = problems;
	}

	@Override
	public boolean visit(TypeDeclaration type) {
		if (!Modifier.isPublic(type.getModifiers())) {
			// non-public types cannot be (or have nested) components
			if (errorLevel.isNone())
				return false;

			Annotation annotation = findComponentAnnotation(type);
			if (annotation != null)
				reportProblem(annotation, null, problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentImplementationClass, type.getName().getIdentifier()), type.getName().getIdentifier());

			return true;
		}

		Annotation annotation = findComponentAnnotation(type);
		if (annotation != null) {
			if (type.isInterface()
					|| Modifier.isAbstract(type.getModifiers())
					|| (!type.isPackageMemberTypeDeclaration() && !isNestedPublicStatic(type))
					|| !hasDefaultConstructor(type)) {
				// interfaces, abstract types, non-static/non-public nested types, or types with no default constructor cannot be components
				reportProblem(annotation, null, problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentImplementationClass, type.getName().getIdentifier()), type.getName().getIdentifier());
			} else {
				ITypeBinding typeBinding = type.resolveBinding();
				if (typeBinding == null) {
					if (debug.isDebugging())
						debug.trace(String.format("Unable to resolve binding for type: %s", type)); //$NON-NLS-1$
				} else {
					IAnnotationBinding annotationBinding = annotation.resolveAnnotationBinding();
					if (annotationBinding == null) {
						if (debug.isDebugging())
							debug.trace(String.format("Unable to resolve binding for annotation: %s", annotation)); //$NON-NLS-1$
					} else {
						IDSModel model = processComponent(type, typeBinding, annotation, annotationBinding, problems);
						models.add(model);
					}
				}
			}
		}

		return true;
	}

	@Override
	public boolean visit(EnumDeclaration node) {
		return visitInvalidElementType(node);
	}

	@Override
	public boolean visit(AnnotationTypeDeclaration node) {
		return visitInvalidElementType(node);
	}

	private boolean visitInvalidElementType(AbstractTypeDeclaration type) {
		Annotation annotation = findComponentAnnotation(type);
		if (annotation != null)
			reportProblem(annotation, null, problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentImplementationClass, type.getName().getIdentifier()), type.getName().getIdentifier());

		return errorLevel != ValidationErrorLevel.none;
	}

	private Annotation findComponentAnnotation(AbstractTypeDeclaration type) {
		for (Object item : type.modifiers()) {
			if (!(item instanceof Annotation))
				continue;

			Annotation annotation = (Annotation) item;
			IAnnotationBinding annotationBinding = annotation.resolveAnnotationBinding();
			if (annotationBinding == null) {
				if (debug.isDebugging())
					debug.trace(String.format("Unable to resolve binding for annotation: %s", annotation)); //$NON-NLS-1$

				continue;
			}

			if (COMPONENT_ANNOTATION.equals(annotationBinding.getAnnotationType().getQualifiedName()))
				return annotation;
		}

		return null;
	}

	private boolean isNestedPublicStatic(TypeDeclaration type) {
		if (Modifier.isStatic(type.getModifiers())) {
			ASTNode parent = type.getParent();
			if (parent != null && parent.getNodeType() == ASTNode.TYPE_DECLARATION) {
				TypeDeclaration parentType = (TypeDeclaration) parent;
				if (Modifier.isPublic(parentType.getModifiers()))
					return parentType.isPackageMemberTypeDeclaration() || isNestedPublicStatic(parentType);
			}
		}

		return false;
	}

	private boolean hasDefaultConstructor(TypeDeclaration type) {
		boolean hasConstructor = false;
		for (MethodDeclaration method : type.getMethods()) {
			if (method.isConstructor()) {
				hasConstructor = true;
				if (Modifier.isPublic(method.getModifiers()) && method.parameters().isEmpty())
					return true;
			}
		}

		return !hasConstructor;
	}

	private IDSModel processComponent(TypeDeclaration type, ITypeBinding typeBinding, Annotation annotation, IAnnotationBinding annotationBinding, Collection<DSAnnotationProblem> problems) {
		HashMap<String, Object> params = new HashMap<String, Object>();
		for (IMemberValuePairBinding pair : annotationBinding.getDeclaredMemberValuePairs()) {
			params.put(pair.getName(), pair.getValue());
		}

		String implClass = typeBinding.getBinaryName();

		String name = implClass;
		Object value;
		if ((value = params.get("name")) instanceof String) { //$NON-NLS-1$
			name = (String) value;
			validateComponentName(annotation, name, problems);
		}

		Collection<String> services;
		if ((value = params.get("service")) instanceof Object[]) { //$NON-NLS-1$
			Object[] elements = (Object[]) value;
			services = new LinkedHashSet<String>(elements.length);
			Map<String, Integer> serviceDuplicates = errorLevel.isNone() ? null : new HashMap<String, Integer>();
			for (int i = 0; i < elements.length; ++i) {
				ITypeBinding serviceType = (ITypeBinding) elements[i];
				String serviceName = serviceType.getBinaryName();
				if (!errorLevel.isNone()) {
					if (serviceDuplicates.containsKey(serviceName)) {
						reportProblem(annotation, "service", i, problems, Messages.AnnotationProcessor_duplicateServiceDeclaration, serviceName); //$NON-NLS-1$
						Integer pos = serviceDuplicates.put(serviceName, null);
						if (pos != null)
							reportProblem(annotation, "service", pos.intValue(), problems, Messages.AnnotationProcessor_duplicateServiceDeclaration, serviceName); //$NON-NLS-1$
					} else {
						serviceDuplicates.put(serviceName, i);
					}
				}

				services.add(serviceName);
				validateComponentService(annotation, typeBinding, serviceType, i, problems);
			}
		} else {
			ITypeBinding[] serviceTypes = typeBinding.getInterfaces();
			services = new ArrayList<String>(serviceTypes.length);
			for (int i = 0; i < serviceTypes.length; ++i) {
				services.add(serviceTypes[i].getBinaryName());
			}
		}

		String factory = null;
		if ((value = params.get("factory")) instanceof String) { //$NON-NLS-1$
			factory = (String) value;
			validateComponentFactory(annotation, factory, problems);
		}

		Boolean serviceFactory = null;
		if ((value = params.get("servicefactory")) instanceof Boolean) { //$NON-NLS-1$
			serviceFactory = (Boolean) value;
		}

		Boolean enabled = null;
		if ((value = params.get("enabled")) instanceof Boolean) { //$NON-NLS-1$
			enabled = (Boolean) value;
		}

		Boolean immediate = null;
		if ((value = params.get("immediate")) instanceof Boolean) { //$NON-NLS-1$
			immediate = (Boolean) value;
		}

		String[] properties;
		if ((value = params.get("property")) instanceof Object[]) { //$NON-NLS-1$
			Object[] elements = (Object[]) value;
			ArrayList<String> list = new ArrayList<String>(elements.length);
			for (int i = 0; i < elements.length; ++i) {
				if (elements[i] instanceof String)
					list.add((String) elements[i]);
			}

			properties = list.toArray(new String[list.size()]);
		} else {
			properties = new String[0];
		}

		String[] propertyFiles;
		if ((value = params.get("properties")) instanceof Object[]) { //$NON-NLS-1$
			Object[] elements = (Object[]) value;
			ArrayList<String> list = new ArrayList<String>(elements.length);
			for (int i = 0; i < elements.length; ++i) {
				if (elements[i] instanceof String)
					list.add((String) elements[i]);
			}

			propertyFiles = list.toArray(new String[list.size()]);
			validateComponentPropertyFiles(annotation, ((IType) typeBinding.getJavaElement()).getJavaProject().getProject(), propertyFiles, problems);
		} else {
			propertyFiles = new String[0];
		}

		String xmlns = null;
		if ((value = params.get("xmlns")) instanceof String) { //$NON-NLS-1$
			xmlns = (String) value;
			validateComponentXMLNS(annotation, xmlns, problems);
		}

		String configPolicy = null;
		if ((value = params.get("configurationPolicy")) instanceof IVariableBinding) { //$NON-NLS-1$
			IVariableBinding configPolicyBinding = (IVariableBinding) value;
			ConfigurationPolicy configPolicyLiteral = ConfigurationPolicy.valueOf(configPolicyBinding.getName());
			if (configPolicyLiteral != null)
				configPolicy = configPolicyLiteral.toString();
		}

		String configPid = null;
		if ((value = params.get("configurationPid")) instanceof String) { //$NON-NLS-1$
			configPid = (String) value;
			validateComponentConfigPID(annotation, configPid, problems);
		}

		DSModel model = new DSModel(new Document(), false);
		IDSComponent component = model.getDSComponent();

		if (xmlns != null)
			component.setNamespace(xmlns);

		if (name != null)
			component.setAttributeName(name);

		if (factory != null)
			component.setFactory(factory);

		if (enabled != null)
			component.setEnabled(enabled.booleanValue());

		if (immediate != null)
			component.setImmediate(immediate.booleanValue());

		if (configPolicy != null)
			component.setConfigurationPolicy(configPolicy);

		if (configPid != null)
			component.setXMLAttribute("configuration-pid", configPid); //$NON-NLS-1$

		IDSDocumentFactory dsFactory = component.getModel().getFactory();
		IDSImplementation impl = dsFactory.createImplementation();
		component.setImplementation(impl);
		impl.setClassName(implClass);

		if (!services.isEmpty()) {
			IDSService service = dsFactory.createService();
			component.setService(service);
			for (String serviceName : services) {
				IDSProvide provide = dsFactory.createProvide();
				service.addProvidedService(provide);
				provide.setInterface(serviceName);
			}

			if (serviceFactory != null)
				service.setServiceFactory(serviceFactory.booleanValue());
		}

		if (properties.length > 0) {
			HashMap<String, IDSProperty> map = new HashMap<String, IDSProperty>(properties.length);
			for (int i = 0; i < properties.length; ++i) {
				String propertyStr = properties[i];
				String[] pair = propertyStr.split("=", 2); //$NON-NLS-1$
				int colon = pair[0].indexOf(':');
				String propertyName, propertyType;
				if (colon == -1) {
					propertyName = pair[0];
					propertyType = null;
				} else {
					propertyName = pair[0].substring(0, colon);
					propertyType = pair[0].substring(colon + 1);
				}

				String propertyValue = pair.length > 1 ? pair[1].trim() : null;

				IDSProperty property = map.get(propertyName);
				if (property == null) {
					// create a new property
					property = dsFactory.createProperty();
					component.addPropertyElement(property);
					map.put(propertyName, property);
					property.setPropertyName(propertyName);
					property.setPropertyType(propertyType);
					property.setPropertyValue(propertyValue);
					validateComponentProperty(annotation, propertyName, propertyType, propertyValue, i, problems);
				} else {
					// property exists; make it multi-valued
					String content = property.getPropertyElemBody();
					if (content == null) {
						content = property.getPropertyValue();
						property.setPropertyElemBody(content);
						property.setPropertyValue(null);
					}

					if (!errorLevel.isNone()) {
						String expected = property.getPropertyType() == null || String.class.getSimpleName().equals(property.getPropertyType()) ? Messages.AnnotationProcessor_stringOrEmpty : property.getPropertyType();
						String actual = propertyType == null || String.class.getSimpleName().equals(propertyType) ? Messages.AnnotationProcessor_stringOrEmpty : propertyType;
						if (!actual.equals(expected))
							reportProblem(annotation, "property", i, problems, NLS.bind(Messages.AnnotationProcessor_inconsistentComponentPropertyType, actual, expected), actual); //$NON-NLS-1$
						else
							validateComponentProperty(annotation, propertyName, propertyType, propertyValue, i, problems);
					}

					if (propertyValue != null)
						property.setPropertyElemBody(content + "\n" + pair[1]); //$NON-NLS-1$
				}
			}
		}

		if (propertyFiles.length > 0) {
			for (String propertyFile : propertyFiles) {
				IDSProperties propertiesElement = dsFactory.createProperties();
				component.addPropertiesElement(propertiesElement);
				propertiesElement.setEntry(propertyFile);
			}
		}

		String activate = null;
		Annotation activateAnnotation = null;
		String deactivate = null;
		Annotation deactivateAnnotation = null;
		String modified = null;
		Annotation modifiedAnnotation = null;

		ArrayList<IDSReference> references = new ArrayList<IDSReference>();
		HashMap<String, Annotation> referenceNames = new HashMap<String, Annotation>();

		for (MethodDeclaration method : type.getMethods()) {
			for (Object modifier : method.modifiers()) {
				if (!(modifier instanceof Annotation))
					continue;

				Annotation methodAnnotation = (Annotation) modifier;
				IAnnotationBinding methodAnnotationBinding = methodAnnotation.resolveAnnotationBinding();
				if (methodAnnotationBinding == null) {
					if (debug.isDebugging())
						debug.trace(String.format("Unable to resolve binding for annotation: %s", methodAnnotation)); //$NON-NLS-1$

					continue;
				}

				String annotationName = methodAnnotationBinding.getAnnotationType().getQualifiedName();

				if (ACTIVATE_ANNOTATION.equals(annotationName)) {
					if (activate == null) {
						activate = method.getName().getIdentifier();
						activateAnnotation = methodAnnotation;
						validateLifeCycleMethod(methodAnnotation, "activate", method, problems); //$NON-NLS-1$
					} else if (!errorLevel.isNone()) {
						reportProblem(methodAnnotation, null, problems, Messages.AnnotationProcessor_duplicateActivateMethod, method.getName().getIdentifier());
						if (activateAnnotation != null) {
							reportProblem(activateAnnotation, null, problems, Messages.AnnotationProcessor_duplicateActivateMethod, activate);
							activateAnnotation = null;
						}
					}

					continue;
				}

				if (DEACTIVATE_ANNOTATION.equals(annotationName)) {
					if (deactivate == null) {
						deactivate = method.getName().getIdentifier();
						deactivateAnnotation = methodAnnotation;
						validateLifeCycleMethod(methodAnnotation, "deactivate", method, problems); //$NON-NLS-1$
					} else if (!errorLevel.isNone()) {
						reportProblem(methodAnnotation, null, problems, Messages.AnnotationProcessor_duplicateDeactivateMethod, method.getName().getIdentifier());
						if (deactivateAnnotation != null) {
							reportProblem(deactivateAnnotation, null, problems, Messages.AnnotationProcessor_duplicateDeactivateMethod, deactivate);
							deactivateAnnotation = null;
						}
					}

					continue;
				}

				if (MODIFIED_ANNOTATION.equals(annotationName)) {
					if (modified == null) {
						modified = method.getName().getIdentifier();
						modifiedAnnotation = methodAnnotation;
						validateLifeCycleMethod(methodAnnotation, "modified", method, problems); //$NON-NLS-1$
					} else if (!errorLevel.isNone()) {
						reportProblem(methodAnnotation, null, problems, Messages.AnnotationProcessor_duplicateModifiedMethod, method.getName().getIdentifier());
						if (modifiedAnnotation != null) {
							reportProblem(modifiedAnnotation, null, problems, Messages.AnnotationProcessor_duplicateModifiedMethod, modified);
							modifiedAnnotation = null;
						}
					}

					continue;
				}

				if (REFERENCE_ANNOTATION.equals(annotationName)) {
					IMethodBinding methodBinding = method.resolveBinding();
					if (methodBinding == null) {
						if (debug.isDebugging())
							debug.trace(String.format("Unable to resolve binding for method: %s", method)); //$NON-NLS-1$
					} else {
						processReference(method, methodBinding, methodAnnotation, methodAnnotationBinding, dsFactory, references, referenceNames, problems);
					}

					continue;
				}
			}
		}

		if (activate != null)
			component.setActivateMethod(activate);

		if (deactivate != null)
			component.setDeactivateMethod(deactivate);

		if (modified != null)
			component.setModifiedeMethod(modified);

		if (!references.isEmpty()) {
			// references must be declared in ascending lexicographical order of their names
			Collections.sort(references, REF_NAME_COMPARATOR);
			for (IDSReference reference : references) {
				component.addReference(reference);
			}
		}

		return model;
	}

	private void validateComponentName(Annotation annotation, String name, Collection<DSAnnotationProblem> problems) {
		if (!errorLevel.isNone() && !PID_PATTERN.matcher(name).matches())
			reportProblem(annotation, "name", problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentName, name), name); //$NON-NLS-1$
	}

	private void validateComponentService(Annotation annotation, ITypeBinding componentType, ITypeBinding serviceType, int index, Collection<DSAnnotationProblem> problems) {
		if (!errorLevel.isNone() && !componentType.isAssignmentCompatible(serviceType))
			reportProblem(annotation, "service", problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentService, serviceType.getName()), serviceType.getName()); //$NON-NLS-1$
	}

	private void validateComponentFactory(Annotation annotation, String factory, Collection<DSAnnotationProblem> problems) {
		if (!errorLevel.isNone() && !PID_PATTERN.matcher(factory).matches())
			reportProblem(annotation, "factory", problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentFactoryName, factory), factory); //$NON-NLS-1$
	}

	private void validateComponentProperty(Annotation annotation, String name, String type, String value, int index, Collection<DSAnnotationProblem> problems) {
		if (errorLevel.isNone())
			return;

		if (PROPERTY_TYPES.contains(type)) {
			if (name == null || name.trim().isEmpty())
				reportProblem(annotation, "property", index, problems, Messages.AnnotationProcessor_invalidComponentProperty_nameRequired, name); //$NON-NLS-1$

			if (value == null) {
				reportProblem(annotation, "property", index, problems, Messages.AnnotationProcessor_invalidComponentProperty_valueRequired, name); //$NON-NLS-1$
			} else {
				try {
					if (Long.class.getSimpleName().equals(type))
						Long.valueOf(value);
					else if (Double.class.getSimpleName().equals(type))
						Double.valueOf(value);
					else if (Float.class.getSimpleName().equals(type))
						Float.valueOf(value);
					else if (Integer.class.getSimpleName().equals(type) || Character.class.getSimpleName().equals(type))
						Integer.valueOf(value);
					else if (Byte.class.getSimpleName().equals(type))
						Byte.valueOf(value);
					else if (Short.class.getSimpleName().equals(type))
						Short.valueOf(value);
				} catch (NumberFormatException e) {
					reportProblem(annotation, "property", index, problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentPropertyValue, type, value), String.valueOf(value)); //$NON-NLS-1$
				}
			}
		} else {
			reportProblem(annotation, "property", index, problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentPropertyType, type), String.valueOf(type)); //$NON-NLS-1$
		}
	}

	private void validateComponentPropertyFiles(Annotation annotation, IProject project, String[] files, Collection<DSAnnotationProblem> problems) {
		if (errorLevel.isNone())
			return;

		for (int i = 0; i < files.length; ++i) {
			String file = files[i];
			IFile wsFile = PDEProject.getBundleRelativeFile(project, new Path(file));
			if (!wsFile.exists())
				reportProblem(annotation, "properties", i, problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentPropertyFile, file), file); //$NON-NLS-1$
		}
	}

	private void validateComponentXMLNS(Annotation annotation, String xmlns, Collection<DSAnnotationProblem> problems) {
		if (!errorLevel.isNone() && IDSConstants.NAMESPACE.equals(xmlns))
			reportProblem(annotation, "xmlns", problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentDescriptorNamespace, xmlns), xmlns); //$NON-NLS-1$
	}

	private void validateComponentConfigPID(Annotation annotation, String configPid, Collection<DSAnnotationProblem> problems) {
		if (!errorLevel.isNone() && !PID_PATTERN.matcher(configPid).matches())
			reportProblem(annotation, "configurationPid", problems, NLS.bind(Messages.AnnotationProcessor_invalidComponentConfigurationPid, configPid), configPid); //$NON-NLS-1$
	}

	private void validateLifeCycleMethod(Annotation annotation, String methodName, MethodDeclaration method, Collection<DSAnnotationProblem> problems) {
		if (errorLevel.isNone())
			return;

		IMethodBinding methodBinding = method.resolveBinding();
		if (methodBinding == null) {
			if (debug.isDebugging())
				debug.trace(String.format("Unable to resolve binding for method: %s", method)); //$NON-NLS-1$

			return;
		}

		String returnTypeName = methodBinding.getReturnType().getName();
		if (!Void.TYPE.getName().equals(returnTypeName))
			reportProblem(annotation, methodName, problems, NLS.bind(Messages.AnnotationProcessor_invalidLifeCycleMethodReturnType, methodName, returnTypeName), returnTypeName);

		ITypeBinding[] paramTypeBindings = methodBinding.getParameterTypes();

		if (paramTypeBindings.length == 0)
			// no-arg method
			return;

		// every argument must be either Map, ComponentContext, or BundleContext
		boolean hasMap = false;
		boolean hasCompCtx = false;
		boolean hasBundleCtx = false;
		boolean hasInt = false;
		for (ITypeBinding paramTypeBinding : paramTypeBindings) {
			String paramTypeName = paramTypeBinding.getErasure().getQualifiedName();
			boolean isDuplicate = false;

			if (Map.class.getName().equals(paramTypeName)) {
				if (hasMap)
					isDuplicate = true;
				else
					hasMap = true;
			} else if (ComponentContext.class.getName().equals(paramTypeName)) {
				if (hasCompCtx)
					isDuplicate = true;
				else
					hasCompCtx = true;
			} else if (BundleContext.class.getName().equals(paramTypeName)) {
				if (hasBundleCtx)
					isDuplicate = true;
				else
					hasBundleCtx = true;
			} else if ("deactivate".equals(methodName) //$NON-NLS-1$
					&& (Integer.class.getName().equals(paramTypeName) || Integer.TYPE.getName().equals(paramTypeName))) {
				if (hasInt)
					isDuplicate = true;
				else
					hasInt = true;
			} else {
				reportProblem(annotation, methodName, problems, NLS.bind(Messages.AnnotationProcessor_invalidLifeCycleMethodParameterType, methodName, paramTypeName), paramTypeName);
			}

			if (isDuplicate)
				reportProblem(annotation, methodName, problems, NLS.bind(Messages.AnnotationProcessor_duplicateLifeCycleMethodParameterType, methodName, paramTypeName), paramTypeName);
		}
	}

	private void processReference(MethodDeclaration method, IMethodBinding methodBinding, Annotation annotation, IAnnotationBinding annotationBinding, IDSDocumentFactory factory, Collection<IDSReference> collector, Map<String, Annotation> names, Collection<DSAnnotationProblem> problems) {
		HashMap<String, Object> params = new HashMap<String, Object>();
		for (IMemberValuePairBinding pair : annotationBinding.getDeclaredMemberValuePairs()) {
			params.put(pair.getName(), pair.getValue());
		}

		ITypeBinding[] argTypes = methodBinding.getParameterTypes();

		ITypeBinding serviceType;
		Object value;
		if ((value = params.get("service")) instanceof ITypeBinding) { //$NON-NLS-1$
			serviceType = (ITypeBinding) value;
			if (!errorLevel.isNone() && argTypes.length > 0) {
				ITypeBinding[] typeArgs;
				if (!(ServiceReference.class.getName().equals(argTypes[0].getErasure().getQualifiedName())
						&& ((typeArgs = argTypes[0].getTypeArguments()).length == 0 || serviceType.isAssignmentCompatible(typeArgs[0])))
						&& !serviceType.isAssignmentCompatible(argTypes[0]))
					reportProblem(annotation, "service", problems, NLS.bind(Messages.AnnotationProcessor_invalidReferenceService, argTypes[0].getName(), serviceType.getName()), serviceType.getName()); //$NON-NLS-1$
			}
		} else if (argTypes.length > 0) {
			if (ServiceReference.class.getName().equals(argTypes[0].getErasure().getQualifiedName())) {
				ITypeBinding[] typeArgs = argTypes[0].getTypeArguments();
				if (typeArgs.length > 0)
					serviceType = typeArgs[0];
				else
					serviceType = null;
			} else {
				serviceType = argTypes[0].isPrimitive() ? getObjectType(method.getAST(), argTypes[0]) : argTypes[0];
			}
		} else {
			serviceType = null;
		}

		if (serviceType == null) {
			reportProblem(annotation, null, problems, Messages.AnnotationProcessor_invalidReferenceServiceUnknown);

			serviceType = method.getAST().resolveWellKnownType(Object.class.getName());
		}

		validateReferenceBindMethod(annotation, serviceType, methodBinding, problems);

		String service = serviceType == null ? null : serviceType.getBinaryName();

		String methodName = methodBinding.getName();
		String name;
		if ((value = params.get("name")) instanceof String) { //$NON-NLS-1$
			name = (String) value;
		} else if (methodName.startsWith("bind")) { //$NON-NLS-1$
			name = methodName.substring("bind".length()); //$NON-NLS-1$
		} else if (methodName.startsWith("set")) { //$NON-NLS-1$
			name = methodName.substring("set".length()); //$NON-NLS-1$
		} else if (methodName.startsWith("add")) { //$NON-NLS-1$
			name = methodName.substring("add".length()); //$NON-NLS-1$
		} else {
			name = methodName;
		}

		if (!errorLevel.isNone()) {
			if (names.containsKey(name)) {
				reportProblem(annotation, "name", problems, NLS.bind(Messages.AnnotationProcessor_duplicateReferenceName, name), name); //$NON-NLS-1$
				Annotation duplicate = names.put(name, null);
				if (duplicate != null)
					reportProblem(duplicate, "name", problems, NLS.bind(Messages.AnnotationProcessor_duplicateReferenceName, name), name); //$NON-NLS-1$
			} else {
				names.put(name, annotation);
			}
		}

		String cardinality = null;
		if ((value = params.get("cardinality")) instanceof IVariableBinding) { //$NON-NLS-1$
			IVariableBinding cardinalityBinding = (IVariableBinding) value;
			ReferenceCardinality cardinalityLiteral = ReferenceCardinality.valueOf(cardinalityBinding.getName());
			if (cardinalityLiteral != null)
				cardinality = cardinalityLiteral.toString();
		}

		String policy = null;
		if ((value = params.get("policy")) instanceof IVariableBinding) { //$NON-NLS-1$
			IVariableBinding policyBinding = (IVariableBinding) value;
			ReferencePolicy policyLiteral = ReferencePolicy.valueOf(policyBinding.getName());
			if (policyLiteral != null)
				policy = policyLiteral.toString();
		}

		String target = null;
		if ((value = params.get("target")) instanceof String) { //$NON-NLS-1$
			target = (String) value;
			validateReferenceTarget(annotation, target, problems);
		}

		String unbind;
		if ((value = params.get("unbind")) instanceof String) { //$NON-NLS-1$
			String unbindValue = (String) value;
			if ("-".equals(unbindValue)) { //$NON-NLS-1$
				unbind = null;
			} else {
				unbind = unbindValue;
				if (!errorLevel.isNone() && serviceType != null) {
					IMethodBinding unbindMethod = findReferenceMethod(methodBinding.getDeclaringClass(), serviceType, unbind);
					if (unbindMethod == null)
						reportProblem(annotation, "unbind", problems, NLS.bind(Messages.AnnotationProcessor_invalidReferenceUnbind, unbind), unbind); //$NON-NLS-1$
				}
			}
		} else if (serviceType != null) {
			String unbindCandidate;
			if (methodName.startsWith("add")) { //$NON-NLS-1$
				unbindCandidate = "remove" + methodName.substring("add".length()); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				unbindCandidate = "un" + methodName; //$NON-NLS-1$
			}

			IMethodBinding unbindMethod = findReferenceMethod(methodBinding.getDeclaringClass(), serviceType, unbindCandidate);
			if (unbindMethod == null)
				unbind = null;
			else
				unbind = unbindMethod.getName();
		} else {
			unbind = null;
		}

		String policyOption = null;
		if ((value = params.get("policyOption")) instanceof IVariableBinding) { //$NON-NLS-1$
			IVariableBinding policyOptionBinding = (IVariableBinding) value;
			ReferencePolicyOption policyOptionLiteral = ReferencePolicyOption.valueOf(policyOptionBinding.getName());
			if (policyOptionLiteral != null)
				policyOption = policyOptionLiteral.toString();
		}

		String updated;
		if ((value = params.get("updated")) instanceof String) { //$NON-NLS-1$
			String updatedValue = (String) value;
			if ("-".equals(updatedValue)) { //$NON-NLS-1$
				updated = null;
			} else {
				updated = updatedValue;
				if (!errorLevel.isNone() && serviceType != null) {
					IMethodBinding updatedMethod = findReferenceMethod(methodBinding.getDeclaringClass(), serviceType, updated);
					if (updatedMethod == null)
						reportProblem(annotation, "updated", problems, NLS.bind(Messages.AnnotationProcessor_invalidReferenceUpdated, updated), updated); //$NON-NLS-1$
				}
			}
		} else if (serviceType != null) {
			String updatedCandidate;
			if (methodName.startsWith("bind")) { //$NON-NLS-1$
				updatedCandidate = "updated" + methodName.substring("bind".length()); //$NON-NLS-1$ //$NON-NLS-2$
			} else if (methodName.startsWith("set")) { //$NON-NLS-1$
				updatedCandidate = "updated" + methodName.substring("set".length()); //$NON-NLS-1$ //$NON-NLS-2$
			} else if (methodName.startsWith("add")) { //$NON-NLS-1$
				updatedCandidate = "updated" + methodName.substring("add".length()); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				updatedCandidate = "updated" + methodName; //$NON-NLS-1$
			}

			IMethodBinding updatedMethod = findReferenceMethod(methodBinding.getDeclaringClass(), serviceType, updatedCandidate);
			if (updatedMethod == null)
				updated = null;
			else
				updated = updatedMethod.getName();
		} else {
			updated = null;
		}

		IDSReference reference = factory.createReference();
		collector.add(reference);

		reference.setReferenceBind(methodName);

		if (name != null)
			reference.setReferenceName(name);

		if (service != null)
			reference.setReferenceInterface(service);

		if (cardinality != null)
			reference.setReferenceCardinality(cardinality);

		if (policy != null)
			reference.setReferencePolicy(policy);

		if (target != null)
			reference.setReferenceTarget(target);

		if (unbind != null)
			reference.setReferenceUnbind(unbind);

		if (policyOption != null)
			reference.setXMLAttribute("policy-option", policyOption); //$NON-NLS-1$

		if (updated != null)
			reference.setXMLAttribute("updated", updated); //$NON-NLS-1$
	}

	private ITypeBinding getObjectType(AST ast, ITypeBinding primitive) {
		if (Boolean.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Boolean.class.getName());

		if (Byte.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Byte.class.getName());

		if (Character.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Character.class.getName());

		if (Double.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Double.class.getName());

		if (Float.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Float.class.getName());

		if (Integer.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Integer.class.getName());

		if (Long.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Long.class.getName());

		if (Short.TYPE.getName().equals(primitive.getName()))
			return ast.resolveWellKnownType(Short.class.getName());

		return null;
	}

	private void validateReferenceBindMethod(Annotation annotation, ITypeBinding serviceType, IMethodBinding methodBinding, Collection<DSAnnotationProblem> problems) {
		if (errorLevel.isNone())
			return;

		String returnTypeName = methodBinding.getReturnType().getName();
		if (!Void.TYPE.getName().equals(returnTypeName))
			reportProblem(annotation, null, problems, NLS.bind(Messages.AnnotationProcessor_invalidBindMethodReturnType, returnTypeName), returnTypeName);

		ITypeBinding[] paramTypeBindings = methodBinding.getParameterTypes();
		if (!(paramTypeBindings.length == 1 && (ServiceReference.class.getName().equals(paramTypeBindings[0].getErasure().getQualifiedName()) || serviceType == null || serviceType.isAssignmentCompatible(paramTypeBindings[0])))
				&& !(paramTypeBindings.length == 2 && (serviceType == null || serviceType.isAssignmentCompatible(paramTypeBindings[0])) && Map.class.getName().equals(paramTypeBindings[1].getErasure().getQualifiedName()))) {
			String[] params = new String[paramTypeBindings.length];
			StringBuilder buf = new StringBuilder(64);
			buf.append('(');
			for (int i = 0; i < params.length; ++i) {
				params[i] = paramTypeBindings[i].getName();
				if (buf.length() > 1)
					buf.append(", "); //$NON-NLS-1$

				buf.append(params[i]);
			}

			buf.append(')');
			reportProblem(annotation, null, problems, NLS.bind(Messages.AnnotationProcessor_invalidBindMethodParameters, buf, serviceType == null ? Messages.AnnotationProcessor_unknownServiceTypeLabel : serviceType.getName()), params);
		}
	}

	private void validateReferenceTarget(Annotation annotation, String target, Collection<DSAnnotationProblem> problems) {
		if (errorLevel.isNone())
			return;

		try {
			FrameworkUtil.createFilter(target);
		} catch (InvalidSyntaxException e) {
			String msg = e.getMessage();
			String suffix = ": " + e.getFilter(); //$NON-NLS-1$
			if (msg.endsWith(suffix))
				msg = msg.substring(0, msg.length() - suffix.length());

			reportProblem(annotation, "target", problems, msg, target); //$NON-NLS-1$
		}
	}

	private IMethodBinding findReferenceMethod(ITypeBinding componentClass, ITypeBinding serviceType, String name) {
		ITypeBinding testedClass = componentClass;

		IMethodBinding candidate = null;
		int priority = 0;
		// priority:
		// 0: <assignment-compatible-type>, Map
		// 1: <exact-type>, Map
		// 2: <assignment-compatible-type>
		// 3: <exact-type>
		do {
			for (IMethodBinding declaredMethod : testedClass.getDeclaredMethods()) {
				if (name.equals(declaredMethod.getName())
						&& Void.TYPE.getName().equals(declaredMethod.getReturnType().getName())
						&& (testedClass.isEqualTo(componentClass)
								|| Modifier.isPublic(declaredMethod.getModifiers())
								|| Modifier.isProtected(declaredMethod.getModifiers())
								|| (!Modifier.isPrivate(declaredMethod.getModifiers())
										&& testedClass.getPackage().isEqualTo(componentClass.getPackage())))) {
					ITypeBinding[] paramTypes = declaredMethod.getParameterTypes();
					if (paramTypes.length == 1) {
						if (ServiceReference.class.getName().equals(paramTypes[0].getErasure().getQualifiedName()))
							// we have the winner
							return declaredMethod;

						if (priority < 3 && serviceType.isEqualTo(paramTypes[0]))
							priority = 3;
						else if (priority < 2 && serviceType.isAssignmentCompatible(paramTypes[0]))
							priority = 2;
						else
							continue;

						// we have a (better) candidate
						candidate = declaredMethod;
					} else if (paramTypes.length == 2) {
						if (priority < 1
								&& serviceType.isEqualTo(paramTypes[0])
								&& Map.class.getName().equals(paramTypes[1].getErasure().getQualifiedName()))
							priority = 1;
						else if (candidate != null
								|| !serviceType.isAssignmentCompatible(paramTypes[0])
								|| !Map.class.getName().equals(paramTypes[1].getErasure().getQualifiedName()))
							continue;

						// we have a candidate
						candidate = declaredMethod;
					}
				}
			}
		} while ((testedClass = testedClass.getSuperclass()) != null);

		return candidate;
	}

	private void reportProblem(Annotation annotation, String member, Collection<DSAnnotationProblem> problems, String message, String... args) {
		reportProblem(annotation, member, -1, problems, message, args);
	}

	private void reportProblem(Annotation annotation, String member, int valueIndex, Collection<DSAnnotationProblem> problems, String message, String... args) {
		if (errorLevel.isNone())
			return;

		Expression memberValue = annotation;
		if (annotation.isNormalAnnotation() && member != null) {
			NormalAnnotation na = (NormalAnnotation) annotation;
			for (Object value : na.values()) {
				MemberValuePair pair = (MemberValuePair) value;
				if (member.equals(pair.getName().getIdentifier())) {
					memberValue = pair.getValue();
					break;
				}
			}
		} else if (annotation.isSingleMemberAnnotation()) {
			SingleMemberAnnotation sma = (SingleMemberAnnotation) annotation;
			memberValue = sma.getValue();
		}

		int start = memberValue.getStartPosition();
		int length = memberValue.getLength();

		if (valueIndex >= 0 && memberValue instanceof ArrayInitializer) {
			ArrayInitializer ai = (ArrayInitializer) memberValue;
			if (valueIndex < ai.expressions().size()) {
				Expression element = (Expression) ai.expressions().get(valueIndex);
				start = element.getStartPosition();
				length = element.getLength();
			}
		}

		if (start >= 0) {
			DSAnnotationProblem problem = new DSAnnotationProblem(errorLevel.isError(), message, args);
			problem.setSourceStart(start);
			problem.setSourceEnd(start + length - 1);
			problems.add(problem);
		}
	}
}