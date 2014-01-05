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
import java.util.Map;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IContainer;
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
import org.eclipse.pde.core.IBaseModel;
import org.eclipse.pde.core.build.IBuildEntry;
import org.eclipse.pde.core.build.IBuildModel;
import org.eclipse.pde.core.build.IBuildModelFactory;
import org.eclipse.pde.internal.core.build.WorkspaceBuildModel;
import org.eclipse.pde.internal.core.ibundle.IBundleModel;
import org.eclipse.pde.internal.core.ibundle.IBundlePluginModelBase;
import org.eclipse.pde.internal.core.natures.PDE;
import org.eclipse.pde.internal.core.project.PDEProject;
import org.eclipse.pde.internal.ds.core.IDSModel;
import org.eclipse.pde.internal.ui.util.ModelModification;
import org.eclipse.pde.internal.ui.util.PDEModelUtility;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.component.annotations.Component;

@SuppressWarnings("restriction")
public class DSAnnotationCompilationParticipant extends CompilationParticipant {

	private static final String DS_BUILDER = "org.eclipse.pde.ds.core.builder"; //$NON-NLS-1$

	private static final String DS_MANIFEST_KEY = "Service-Component"; //$NON-NLS-1$

	static final String COMPONENT_ANNOTATION = Component.class.getName();

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

		String errorLevelStr = Platform.getPreferencesService().getString(Activator.PLUGIN_ID, Activator.PREF_VALIDATION_ERROR_LEVEL, ValidationErrorLevel.error.name(), new IScopeContext[] { new ProjectScope(project.getProject()), InstanceScope.INSTANCE });
		ValidationErrorLevel errorLevel;
		try {
			errorLevel = ValidationErrorLevel.valueOf(errorLevelStr);
		} catch (IllegalArgumentException e) {
			errorLevel = ValidationErrorLevel.error;
		}

		if (errorLevel != state.getErrorLevel()) {
			state.setErrorLevel(errorLevel);
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
		HashMap<IJavaProject, Map<ICompilationUnit, BuildContext>> filesByProject = new HashMap<IJavaProject, Map<ICompilationUnit, BuildContext>>();
		for (BuildContext file : files) {
			ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file.getFile());
			if (cu == null)
				continue;

			Map<ICompilationUnit, BuildContext> map = filesByProject.get(cu.getJavaProject());
			if (map == null) {
				map = new HashMap<ICompilationUnit, BuildContext>();
				filesByProject.put(cu.getJavaProject(), map);
			}

			map.put(cu, file);
		}

		// process all CUs in each project
		for (Map.Entry<IJavaProject, Map<ICompilationUnit, BuildContext>> entry : filesByProject.entrySet()) {
			processAnnotations(entry.getKey(), entry.getValue());
		}
	}

	private void processAnnotations(IJavaProject javaProject, Map<ICompilationUnit, BuildContext> fileMap) {
		ASTParser parser = ASTParser.newParser(AST.JLS8);
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setProject(javaProject);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);

		ProjectContext projectContext = processingContext.get(javaProject);
		ProjectState state = projectContext.getState();

		parser.setIgnoreMethodBodies(state.getErrorLevel() == ValidationErrorLevel.none);

		ICompilationUnit[] cuArr = fileMap.keySet().toArray(new ICompilationUnit[fileMap.size()]);
		Map<ICompilationUnit, Collection<IDSModel>> models = new HashMap<ICompilationUnit, Collection<IDSModel>>();
		parser.createASTs(cuArr, new String[0], new AnnotationProcessor(models, fileMap, state.getErrorLevel()), null);

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
					try {
						ensureExists(folder);
					} catch (CoreException e) {
						Activator.getDefault().getLog().log(e.getStatus());
						model.dispose();
						continue;
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

	private void ensureExists(IFolder folder) throws CoreException {
		if (folder.exists())
			return;

		IContainer parent = folder.getParent();
		if (parent != null && parent.getType() == IResource.FOLDER)
			ensureExists((IFolder) parent);

		folder.create(true, true, null);
	}

	public static boolean isManaged(IProject project) {
		try {
			return project.getSessionProperty(PROP_STATE) != null;
		} catch (CoreException e) {
			return false;
		}
	}
}
