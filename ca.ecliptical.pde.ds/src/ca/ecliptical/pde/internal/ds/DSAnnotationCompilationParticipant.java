/*******************************************************************************
 * Copyright (c) 2012, 2013 Ecliptical Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ecliptical Software Inc. - initial API and implementation
 *******************************************************************************/
package ca.ecliptical.pde.internal.ds;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.BuildContext;
import org.eclipse.jdt.core.compiler.CompilationParticipant;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jface.text.Document;
import org.eclipse.pde.core.IBaseModel;
import org.eclipse.pde.core.build.IBuildEntry;
import org.eclipse.pde.core.build.IBuildModel;
import org.eclipse.pde.core.build.IBuildModelFactory;
import org.eclipse.pde.internal.core.build.WorkspaceBuildModel;
import org.eclipse.pde.internal.core.ibundle.IBundleModel;
import org.eclipse.pde.internal.core.ibundle.IBundlePluginModelBase;
import org.eclipse.pde.internal.core.natures.PDE;
import org.eclipse.pde.internal.core.project.PDEProject;
import org.eclipse.pde.internal.ds.core.IDSComponent;
import org.eclipse.pde.internal.ds.core.IDSDocumentFactory;
import org.eclipse.pde.internal.ds.core.IDSImplementation;
import org.eclipse.pde.internal.ds.core.IDSModel;
import org.eclipse.pde.internal.ds.core.IDSProperties;
import org.eclipse.pde.internal.ds.core.IDSProperty;
import org.eclipse.pde.internal.ds.core.IDSProvide;
import org.eclipse.pde.internal.ds.core.IDSReference;
import org.eclipse.pde.internal.ds.core.IDSService;
import org.eclipse.pde.internal.ds.core.text.DSModel;
import org.eclipse.pde.internal.ui.util.ModelModification;
import org.eclipse.pde.internal.ui.util.PDEModelUtility;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

@SuppressWarnings("restriction")
public class DSAnnotationCompilationParticipant extends CompilationParticipant {

	private static final String DS_BUILDER = "org.eclipse.pde.ds.core.builder"; //$NON-NLS-1$

	private static final String DS_MANIFEST_KEY = "Service-Component"; //$NON-NLS-1$

	private static final String COMPONENT_ANNOTATION = Component.class.getName();

	private static final String ACTIVATE_ANNOTATION = Activate.class.getName();

	private static final String MODIFIED_ANNOTATION = Modified.class.getName();

	private static final String DEACTIVATE_ANNOTATION = Deactivate.class.getName();

	private static final String REFERENCE_ANNOTATION = Reference.class.getName();

	private static final QualifiedName PROP_STATE = new QualifiedName(Activator.PLUGIN_ID, "state"); //$NON-NLS-1$

	private static final String STATE_FILENAME = "state.dat"; //$NON-NLS-1$

	private static final Debug debug = Debug.getDebug("ds-annotation-builder"); //$NON-NLS-1$

	private final Map<IJavaProject, ProjectContext> processingContext = Collections.synchronizedMap(new HashMap<IJavaProject, ProjectContext>());

	@Override
	public boolean isAnnotationProcessor() {
		return true;
	}

	@Override
	public boolean isActive(IJavaProject project) {
		boolean enabled = Platform.getPreferencesService().getBoolean(Activator.PLUGIN_ID, Activator.PREF_ENABLED, true, new IScopeContext[] { new ProjectScope(project.getProject()), InstanceScope.INSTANCE });
		if (!enabled)
			return false;

		if (!PDE.hasPluginNature(project.getProject()))
			return false;

		try {
			IType annotationType = project.findType(COMPONENT_ANNOTATION);
			return annotationType != null && annotationType.isAnnotation();
		} catch (JavaModelException e) {
			Activator.getDefault().getLog().log(e.getStatus());
		}

		return false;
	}

	@Override
	public int aboutToBuild(IJavaProject project) {
		if (debug.isDebugging())
			debug.trace(String.format("About to build project: %s", project.getElementName())); //$NON-NLS-1$

		int result = READY_FOR_BUILD;

		ProjectState state = null;
		try {
			Object value = project.getProject().getSessionProperty(PROP_STATE);
			if (value instanceof SoftReference<?>) {
				@SuppressWarnings("unchecked")
				SoftReference<ProjectState> ref = (SoftReference<ProjectState>) value;
				state = ref.get();
			}
		} catch (CoreException e) {
			Activator.getDefault().getLog().log(e.getStatus());
		}

		if (state == null) {
			try {
				state = loadState(project.getProject());
			} catch (IOException e) {
				Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Error loading project state.", e)); //$NON-NLS-1$
			}

			if (state == null) {
				state = new ProjectState();
				result = NEEDS_FULL_BUILD;
			}

			try {
				project.getProject().setSessionProperty(PROP_STATE, new SoftReference<ProjectState>(state));
			} catch (CoreException e) {
				Activator.getDefault().getLog().log(e.getStatus());
			}
		}

		processingContext.put(project, new ProjectContext(state));

		String path = Platform.getPreferencesService().getString(Activator.PLUGIN_ID, Activator.PREF_PATH, Activator.DEFAULT_PATH, new IScopeContext[] { new ProjectScope(project.getProject()), InstanceScope.INSTANCE });
		if (!path.equals(state.getPath())) {
			state.setPath(path);
			result = NEEDS_FULL_BUILD;
		}

		return result;
	}

	private ProjectState loadState(IProject project) throws IOException {
		File workDir = project.getWorkingLocation(Activator.PLUGIN_ID).toFile();
		File stateFile = new File(workDir, STATE_FILENAME);
		if (!stateFile.canRead()) {
			if (debug.isDebugging())
				debug.trace(String.format("Missing or invalid project state file: %s", stateFile)); //$NON-NLS-1$

			return null;
		}

		ObjectInputStream in = new ObjectInputStream(new FileInputStream(stateFile));
		try {
			ProjectState value = (ProjectState) in.readObject();

			if (debug.isDebugging()) {
				debug.trace(String.format("Loaded state for project: %s", project.getName())); //$NON-NLS-1$
				for (Map.Entry<String, Collection<String>> entry : value.getMappings().entrySet())
					debug.trace(String.format("%s -> %s", entry.getKey(), entry.getValue())); //$NON-NLS-1$
			}

			return value;
		} catch (ClassNotFoundException e) {
			throw new IOException("Unable to deserialize project state.", e); //$NON-NLS-1$
		} finally {
			in.close();
		}
	}

	@Override
	public void buildFinished(IJavaProject project) {
		ProjectContext projectContext = processingContext.remove(project);
		if (projectContext != null) {
			ProjectState state = projectContext.getState();
			// check if unprocessed CUs still exist; if not, their mapped files are now abandoned
			Map<String, Collection<String>> cuMap = state.getMappings();
			HashSet<String> abandoned = new HashSet<String>(projectContext.getAbandoned());
			for (String cuKey : projectContext.getUnprocessed()) {
				boolean exists = false;
				try {
					IType cuType = project.findType(cuKey);
					IResource file;
					if (cuType != null && (file = cuType.getResource()) != null && file.exists())
						exists = true;
				} catch (JavaModelException e) {
					Activator.getDefault().getLog().log(e.getStatus());
				}

				if (!exists) {
					if (debug.isDebugging())
						debug.trace(String.format("Mapped CU %s no longer exists.", cuKey)); //$NON-NLS-1$

					Collection<String> dsKeys = cuMap.remove(cuKey);
					if (dsKeys != null)
						abandoned.addAll(dsKeys);
				}
			}

			// remove CUs with no mapped DS models
			HashSet<String> retained = new HashSet<String>();
			for (Iterator<Map.Entry<String, Collection<String>>> i = cuMap.entrySet().iterator(); i.hasNext();) {
				Map.Entry<String, Collection<String>> entry = i.next();
				Collection<String> dsKeys = entry.getValue();
				if (dsKeys.isEmpty())
					i.remove();
				else
					retained.addAll(dsKeys);
			}

			// retain abandoned files that are still mapped elsewhere
			abandoned.removeAll(retained);

			if (projectContext.isChanged()) {
				try {
					saveState(project.getProject(), state);
				} catch (IOException e) {
					Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Error saving file mappings.", e)); //$NON-NLS-1$
				}
			}

			// delete all abandoned files
			ArrayList<IStatus> deleteStatuses = new ArrayList<IStatus>(2);
			for (String dsKey : abandoned) {
				IPath path = Path.fromPortableString(dsKey);

				if (debug.isDebugging())
					debug.trace(String.format("Deleting %s", path)); //$NON-NLS-1$

				IFile file = PDEProject.getBundleRelativeFile(project.getProject(), path);
				if (file.exists()) {
					try {
						file.delete(true, null);
					} catch (CoreException e) {
						deleteStatuses.add(e.getStatus());
					}
				}
			}

			if (!deleteStatuses.isEmpty())
				Activator.getDefault().getLog().log(new MultiStatus(Activator.PLUGIN_ID, 0, deleteStatuses.toArray(new IStatus[deleteStatuses.size()]), "Error deleting generated files.", null)); //$NON-NLS-1$

			writeManifest(project.getProject(), retained, abandoned);
			writeBuildProperties(project.getProject(), retained, abandoned);
		}

		if (debug.isDebugging())
			debug.trace(String.format("Build finished for project: %s", project.getElementName())); //$NON-NLS-1$
	}

	private void saveState(IProject project, ProjectState state) throws IOException {
		File workDir = project.getWorkingLocation(Activator.PLUGIN_ID).toFile();
		File stateFile = new File(workDir, STATE_FILENAME);

		if (debug.isDebugging()) {
			debug.trace(String.format("Saving state for project: %s", project.getName())); //$NON-NLS-1$
			for (Map.Entry<String, Collection<String>> entry : state.getMappings().entrySet())
				debug.trace(String.format("%s -> %s", entry.getKey(), entry.getValue())); //$NON-NLS-1$
		}

		ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(stateFile));
		try {
			out.writeObject(state);
		} finally {
			out.close();
		}
	}

	private void writeManifest(IProject project, final Collection<String> retained, final Collection<String> abandoned) {
		PDEModelUtility.modifyModel(new ModelModification(project) {
			@Override
			protected void modifyModel(IBaseModel model, IProgressMonitor monitor) throws CoreException {
				if (model instanceof IBundlePluginModelBase)
					updateManifest((IBundlePluginModelBase) model, retained, abandoned);
			}
		}, null);
	}

	private void updateManifest(IBundlePluginModelBase model, Collection<String> retained, Collection<String> abandoned) {
		IBundleModel bundleModel = model.getBundleModel();
		LinkedHashSet<IPath> entries = new LinkedHashSet<IPath>();
		collectManifestEntries(bundleModel, entries);

		boolean changed = false;
		for (String dsKey : abandoned) {
			IPath path = Path.fromPortableString(dsKey);
			changed |= entries.remove(path);
		}

		for (String dsKey : retained) {
			IPath path = Path.fromPortableString(dsKey);
			if (!isManifestEntryIncluded(entries, path))
				changed |= entries.add(path);
		}

		if (!changed)
			return;

		StringBuilder buf = new StringBuilder();
		for (IPath entry : entries) {
			if (buf.length() > 0)
				buf.append(",\n "); //$NON-NLS-1$

			buf.append(entry.toString());
		}

		String value = buf.length() == 0 ? null : buf.toString();

		if (debug.isDebugging())
			debug.trace(String.format("Setting manifest header in %s to %s: %s", model.getUnderlyingResource().getFullPath(), DS_MANIFEST_KEY, value)); //$NON-NLS-1$

		bundleModel.getBundle().setHeader(DS_MANIFEST_KEY, value);
	}

	private void collectManifestEntries(IBundleModel bundleModel, Collection<IPath> entries) {
		String header = bundleModel.getBundle().getHeader(DS_MANIFEST_KEY);
		if (header == null)
			return;

		String[] elements = header.split("\\s*,\\s*"); //$NON-NLS-1$
		for (String element : elements) {
			if (!element.isEmpty())
				entries.add(new Path(element));
		}
	}

	private boolean isManifestEntryIncluded(Collection<IPath> entries, IPath path) {
		for (IPath entry : entries) {
			if (entry.equals(path))
				return true;

			if (entry.removeLastSegments(1).equals(path.removeLastSegments(1))) {
				// check if wildcard match (last path segment)
				Filter filter;
				try {
					filter = FrameworkUtil.createFilter("(filename=" + sanitizeFilterValue(entry.lastSegment()) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (InvalidSyntaxException e) {
					continue;
				}

				if (filter.matches(Collections.singletonMap("filename", path.lastSegment()))) //$NON-NLS-1$
					return true;
			}
		}

		return false;
	}

	private String sanitizeFilterValue(String value) {
		return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	private void writeBuildProperties(final IProject project, final Collection<String> retained, final Collection<String> abandoned) {
		//		PDEModelUtility.modifyModel(new ModelModification(PDEProject.getBuildProperties(project)) {
		//			@Override
		//			protected void modifyModel(IBaseModel model, IProgressMonitor monitor) throws CoreException {
		//				if (model instanceof IBuildModel) {
		IFile file = PDEProject.getBuildProperties(project);
		if (!file.exists())
			return;

		WorkspaceBuildModel wbm = new WorkspaceBuildModel(file);
		wbm.load();
		if (!wbm.isLoaded())
			return;

		try {
			updateBuildProperties(wbm, retained, abandoned);
		} catch (CoreException e) {
			Activator.getDefault().getLog().log(e.getStatus());
		}

		if (wbm.isDirty()) {
			if (debug.isDebugging())
				debug.trace(String.format("Updating %s with %s", file.getFullPath(), wbm.getBuild().getEntry(IBuildEntry.BIN_INCLUDES))); //$NON-NLS-1$

			wbm.save();
		}
		//				}
		//			}
		//		}, null);
	}

	private void updateBuildProperties(IBuildModel model, Collection<String> retained, Collection<String> abandoned) throws CoreException {
		IBuildEntry includes = model.getBuild().getEntry(IBuildEntry.BIN_INCLUDES);

		if (includes != null) {
			for (String dsKey : abandoned) {
				String path = Path.fromPortableString(dsKey).toString();
				if (includes.contains(path))
					includes.removeToken(path);
			}
		}

		if (!retained.isEmpty()) {
			if (includes == null) {
				IBuildModelFactory factory = model.getFactory();
				includes = factory.createEntry(IBuildEntry.BIN_INCLUDES);
				model.getBuild().add(includes);
			}

			LinkedHashSet<IPath> entries = new LinkedHashSet<IPath>();
			collectBuildEntries(includes, entries);

			for (String dsKey : retained) {
				IPath path = Path.fromPortableString(dsKey);
				if (!isBuildEntryIncluded(entries, path))
					includes.addToken(path.toString());
			}
		}
	}

	private void collectBuildEntries(IBuildEntry includes, Collection<IPath> entries) {
		if (includes == null)
			return;

		for (String include : includes.getTokens()) {
			if (!(include = include.trim()).isEmpty())
				entries.add(new Path(include));
		}
	}

	private boolean isBuildEntryIncluded(Collection<IPath> entries, IPath path) {
		for (IPath entry : entries) {
			if (entry.equals(path))
				return true;

			if (entry.hasTrailingSeparator() && entry.isPrefixOf(path))
				return true;

			// TODO support full Ant path patterns
		}

		return false;
	}

	@Override
	public void processAnnotations(BuildContext[] files) {
		// we need to process CUs in context of a project; separate them by project
		HashMap<IJavaProject, List<ICompilationUnit>> filesByProject = new HashMap<IJavaProject, List<ICompilationUnit>>();
		for (BuildContext file : files) {
			ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file.getFile());
			if (cu == null)
				continue;

			List<ICompilationUnit> list = filesByProject.get(cu.getJavaProject());
			if (list == null) {
				list = new ArrayList<ICompilationUnit>();
				filesByProject.put(cu.getJavaProject(), list);
			}

			list.add(cu);
		}

		// process all CUs in each project
		for (Map.Entry<IJavaProject, List<ICompilationUnit>> entry : filesByProject.entrySet()) {
			processAnnotations(entry.getKey(), entry.getValue());
		}
	}

	private void processAnnotations(IJavaProject javaProject, List<ICompilationUnit> cuList) {
		ASTParser parser = ASTParser.newParser(AST.JLS4);
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setProject(javaProject);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setIgnoreMethodBodies(true);

		ICompilationUnit[] cuArr = cuList.toArray(new ICompilationUnit[cuList.size()]);
		Map<ICompilationUnit, Collection<IDSModel>> models = new HashMap<ICompilationUnit, Collection<IDSModel>>();
		parser.createASTs(cuArr, new String[0], new AnnotationProcessor(models), null);

		ProjectContext projectContext = processingContext.get(javaProject);
		ProjectState state = projectContext.getState();
		Map<String, Collection<String>> cuMap = state.getMappings();
		Collection<String> unprocessed = projectContext.getUnprocessed();
		Collection<String> abandoned = projectContext.getAbandoned();

		IPath outputPath = new Path(state.getPath()).addTrailingSeparator();

		// save each model to a file; track changes to mappings
		for (Map.Entry<ICompilationUnit, Collection<IDSModel>> entry : models.entrySet()) {
			ICompilationUnit cu = entry.getKey();
			IType cuType = cu.findPrimaryType();
			if (cuType == null) {
				if (debug.isDebugging())
					debug.trace(String.format("CU %s has no primary type!", cu.getElementName())); //$NON-NLS-1$

				continue;	// should never happen
			}

			String cuKey = cuType.getFullyQualifiedName();

			unprocessed.remove(cuKey);
			Collection<String> oldDSKeys = cuMap.remove(cuKey);
			Collection<String> dsKeys = new HashSet<String>();
			cuMap.put(cuKey, dsKeys);

			for (IDSModel model : entry.getValue()) {
				String compName = model.getDSComponent().getAttributeName();
				IPath filePath = outputPath.append(compName).addFileExtension("xml"); //$NON-NLS-1$
				String dsKey = filePath.toPortableString();

				// exclude file from garbage collection
				if (oldDSKeys != null)
					oldDSKeys.remove(dsKey);

				// add file to CU mapping
				dsKeys.add(dsKey);

				// actually save the file
				IFile compFile = PDEProject.getBundleRelativeFile(javaProject.getProject(), filePath);
				model.setUnderlyingResource(compFile);

				try {
					ensureDSProject(compFile.getProject());
				} catch (CoreException e) {
					Activator.getDefault().getLog().log(e.getStatus());
				}

				IPath parentPath = compFile.getParent().getProjectRelativePath();
				if (!parentPath.isEmpty()) {
					IFolder folder = javaProject.getProject().getFolder(parentPath);
					if (!folder.exists()) {
						try {
							folder.create(true, true, null);
						} catch (CoreException e) {
							Activator.getDefault().getLog().log(e.getStatus());
							model.dispose();
							continue;
						}
					}
				}

				if (debug.isDebugging())
					debug.trace(String.format("Saving model: %s", compFile.getFullPath())); //$NON-NLS-1$

				model.save();
				model.dispose();
			}

			// track abandoned files (may be garbage)
			if (oldDSKeys != null)
				abandoned.addAll(oldDSKeys);
		}
	}

	private void ensureDSProject(IProject project) throws CoreException {
		IProjectDescription description = project.getDescription();
		ICommand[] commands = description.getBuildSpec();

		for (ICommand command : commands) {
			if (DS_BUILDER.equals(command.getBuilderName()))
				return;
		}

		ICommand[] newCommands = new ICommand[commands.length + 1];
		System.arraycopy(commands, 0, newCommands, 0, commands.length);
		ICommand command = description.newCommand();
		command.setBuilderName(DS_BUILDER);
		newCommands[newCommands.length - 1] = command;
		description.setBuildSpec(newCommands);
		project.setDescription(description, null);
	}

	public static boolean isManaged(IProject project) {
		try {
			return project.getSessionProperty(PROP_STATE) != null;
		} catch (CoreException e) {
			return false;
		}
	}

	private static class AnnotationProcessor extends ASTRequestor {

		private final Map<ICompilationUnit, Collection<IDSModel>> models;

		public AnnotationProcessor(Map<ICompilationUnit, Collection<IDSModel>> models) {
			this.models = models;
		}

		@Override
		public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
			models.put(source, new HashSet<IDSModel>());

			for (Object element : ast.types()) {
				if (!(element instanceof TypeDeclaration))
					continue;

				visit(source, (TypeDeclaration) element);
			}
		}

		private void visit(ICompilationUnit cu, TypeDeclaration type) {
			if (type.isInterface()
					|| type.isLocalTypeDeclaration()
					|| (type.getModifiers() & Modifier.PUBLIC) == 0)
				return;

			for (Object element : type.getTypes()) {
				if (!(element instanceof TypeDeclaration))
					continue;

				visit(cu, (TypeDeclaration) element);
			}

			if ((type.getModifiers() & Modifier.ABSTRACT) != 0
					|| (!type.isPackageMemberTypeDeclaration()
							&& (type.getModifiers() & Modifier.STATIC) == 0))
				return;

			for (Object item : type.modifiers()) {
				if (!(item instanceof Annotation))
					continue;

				Annotation annotation = (Annotation) item;
				IAnnotationBinding binding = annotation.resolveAnnotationBinding();
				if (COMPONENT_ANNOTATION.equals(binding.getAnnotationType().getQualifiedName())) {
					IDSModel model = processComponent(type.resolveBinding(), binding);
					Collection<IDSModel> values = models.get(cu);
					values.add(model);
					return;
				}
			}
		}

		private IDSModel processComponent(ITypeBinding type, IAnnotationBinding annotation) {
			HashMap<String, Object> params = new HashMap<String, Object>();
			for (IMemberValuePairBinding pair : annotation.getDeclaredMemberValuePairs()) {
				params.put(pair.getName(), pair.getValue());
			}

			String implClass = ((IType) type.getJavaElement()).getFullyQualifiedName();

			String name = implClass;
			Object value;
			if ((value = params.get("name")) instanceof String) { //$NON-NLS-1$
				name = (String) value;
			}

			String[] services;
			if ((value = params.get("service")) instanceof Object[]) { //$NON-NLS-1$
				Object[] elements = (Object[]) value;
				services = new String[elements.length];
				for (int i = 0; i < elements.length; ++i) {
					ITypeBinding serviceType = (ITypeBinding) elements[i];
					services[i] = ((IType) serviceType.getJavaElement()).getFullyQualifiedName();
				}
			} else {
				ITypeBinding[] serviceTypes = type.getInterfaces();
				services = new String[serviceTypes.length];
				for (int i = 0; i < serviceTypes.length; ++i) {
					services[i] = ((IType) serviceTypes[i].getJavaElement()).getFullyQualifiedName();
				}
			}

			String factory = null;
			if ((value = params.get("factory")) instanceof String) { //$NON-NLS-1$
				factory = (String) value;
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
				properties = new String[elements.length];
				System.arraycopy(elements, 0, properties, 0, elements.length);
			} else {
				properties = new String[0];
			}

			String[] propertyFiles;
			if ((value = params.get("properties")) instanceof Object[]) { //$NON-NLS-1$
				Object[] elements = (Object[]) value;
				propertyFiles = new String[elements.length];
				System.arraycopy(elements, 0, propertyFiles, 0, elements.length);
			} else {
				propertyFiles = new String[0];
			}

			String xmlns = null;
			if ((value = params.get("xmlns")) instanceof String) { //$NON-NLS-1$
				xmlns = (String) value;
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

			if (services.length > 0) {
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
				for (String propertyStr : properties) {
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

					IDSProperty property = map.get(propertyName);
					if (property == null) {
						// create a new property
						property = dsFactory.createProperty();
						component.addPropertyElement(property);
						map.put(propertyName, property);
						property.setPropertyName(propertyName);
						property.setPropertyType(propertyType);
						if (pair.length > 1)
							property.setPropertyValue(pair[1]);
					} else {
						// property exists; make it multi-valued
						String content = property.getPropertyElemBody();
						if (content == null) {
							content = property.getPropertyValue();
							property.setPropertyElemBody(content);
							property.setPropertyValue(null);
						}

						if (pair.length > 0)
							property.setPropertyElemBody(content + " " + pair[1]); //$NON-NLS-1$
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
			String deactivate = null;
			String modified = null;
			for (IMethodBinding method : type.getDeclaredMethods()) {
				for (IAnnotationBinding methodAnnotation : method.getAnnotations()) {
					String annotationName = methodAnnotation.getAnnotationType().getQualifiedName();
					if (ACTIVATE_ANNOTATION.equals(annotationName)) {
						activate = method.getName();
						break;
					}

					if (DEACTIVATE_ANNOTATION.equals(annotationName)) {
						deactivate = method.getName();
						break;
					}

					if (MODIFIED_ANNOTATION.equals(annotationName)) {
						modified = method.getName();
						break;
					}

					if (REFERENCE_ANNOTATION.equals(annotationName)) {
						processReference(method, methodAnnotation, component);
						break;
					}
				}
			}

			if (activate != null)
				component.setActivateMethod(activate);

			if (deactivate != null)
				component.setDeactivateMethod(deactivate);

			if (modified != null)
				component.setModifiedeMethod(modified);

			return model;
		}

		private void processReference(IMethodBinding method, IAnnotationBinding annotation, IDSComponent component) {
			ITypeBinding[] argTypes = method.getParameterTypes();
			if (argTypes.length < 1)
				return;

			HashMap<String, Object> params = new HashMap<String, Object>();
			for (IMemberValuePairBinding pair : annotation.getDeclaredMemberValuePairs()) {
				params.put(pair.getName(), pair.getValue());
			}

			String name;
			Object value;
			if ((value = params.get("name")) instanceof String) { //$NON-NLS-1$
				name = (String) value;
			} else if (method.getName().startsWith("bind")) { //$NON-NLS-1$
				name = method.getName().substring("bind".length()); //$NON-NLS-1$
			} else if (method.getName().startsWith("set")) { //$NON-NLS-1$
				name = method.getName().substring("set".length()); //$NON-NLS-1$
			} else if (method.getName().startsWith("add")) { //$NON-NLS-1$
				name = method.getName().substring("add".length()); //$NON-NLS-1$
			} else {
				name = method.getName();
			}

			String service;
			if ((value = params.get("service")) instanceof ITypeBinding) { //$NON-NLS-1$
				ITypeBinding serviceType = (ITypeBinding) value;
				service = ((IType) serviceType.getJavaElement()).getFullyQualifiedName();
			} else {
				service = ((IType) argTypes[0].getJavaElement()).getFullyQualifiedName();
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
			}

			String unbind = null;
			if ((value = params.get("unbind")) instanceof String) { //$NON-NLS-1$
				unbind = (String) value;
			}

			String policyOption = null;
			if ((value = params.get("policyOption")) instanceof IVariableBinding) { //$NON-NLS-1$
				IVariableBinding policyOptionBinding = (IVariableBinding) value;
				ReferencePolicyOption policyOptionLiteral = ReferencePolicyOption.valueOf(policyOptionBinding.getName());
				if (policyOptionLiteral != null)
					policyOption = policyOptionLiteral.toString();
			}

			String updated = null;
			if ((value = params.get("updated")) instanceof String) { //$NON-NLS-1$
				updated = (String) value;
			}

			IDSReference reference = component.getModel().getFactory().createReference();
			component.addReference(reference);

			reference.setReferenceBind(method.getName());

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
	}

	private static class ProjectContext {

		private final ProjectState state;

		// DS files abandoned since last run
		private final Collection<String> abandoned = new HashSet<String>();

		// CUs not processed in this run
		private final Collection<String> unprocessed;

		private final Map<String, Collection<String>> oldMappings;

		public ProjectContext(ProjectState state) {
			this.state = state;

			// track unprocessed CUs from the start
			unprocessed = new HashSet<String>(state.getMappings().keySet());

			// deep-copy existing mappings so later we can determine if changed
			Map<String, Collection<String>> mappings = state.getMappings();
			oldMappings = new HashMap<String, Collection<String>>(mappings.size());
			for (Map.Entry<String, Collection<String>> entry : mappings.entrySet()) {
				oldMappings.put(entry.getKey(), new HashSet<String>(entry.getValue()));
			}
		}

		public boolean isChanged() {
			return !oldMappings.equals(state.getMappings());
		}

		public ProjectState getState() {
			return state;
		}

		public Collection<String> getAbandoned() {
			return abandoned;
		}

		public Collection<String> getUnprocessed() {
			return unprocessed;
		}
	}
}
