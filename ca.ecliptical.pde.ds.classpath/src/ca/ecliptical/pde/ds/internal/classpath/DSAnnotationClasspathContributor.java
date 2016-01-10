/*******************************************************************************
 * Copyright (c) 2015 Ecliptical Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Ecliptical Software Inc. - initial API and implementation
 *******************************************************************************/
package ca.ecliptical.pde.ds.internal.classpath;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.pde.core.IClasspathContributor;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import ca.ecliptical.pde.ds.classpath.Constants;

public class DSAnnotationClasspathContributor implements IClasspathContributor {

	// private static final IAccessRule[] ANNOTATION_ACCESS_RULES = { JavaCore.newAccessRule(new Path("org/osgi/service/component/annotations/*"), IAccessRule.K_DISCOURAGED | IAccessRule.IGNORE_IF_BETTER) };
	private static final IAccessRule[] ANNOTATION_ACCESS_RULES = { };

	private static final IClasspathAttribute[] DS_ATTRS = { JavaCore.newClasspathAttribute(Constants.CP_ATTRIBUTE, Boolean.toString(true)) };

	public List<IClasspathEntry> getInitialEntries(BundleDescription project) {
		BundleContext ctx = Activator.getContext();
		if (ctx != null) {
			IPluginModelBase model = PluginRegistry.findModel(project);
			if (model != null) {
				IResource resource = model.getUnderlyingResource();
				if (resource != null) {
					boolean autoClasspath = Platform.getPreferencesService().getBoolean(Activator.PREFS_QUALIFIER, Constants.PREF_CLASSPATH, true, new IScopeContext[] { new ProjectScope(resource.getProject()), InstanceScope.INSTANCE });
					if (autoClasspath) {
						Bundle bundle = ctx.getBundle();
						try {
							URL fileURL = FileLocator.toFileURL(bundle.getEntry("org.osgi.service.component.annotations-1.3.0.jar")); //$NON-NLS-1$
							if ("file".equals(fileURL.getProtocol())) { //$NON-NLS-1$
								URL srcFileURL = FileLocator.toFileURL(bundle.getEntry("annotationssrc.zip")); //$NON-NLS-1$
								IPath srcPath = "file".equals(srcFileURL.getProtocol()) ? new Path(srcFileURL.getPath()) : null; //$NON-NLS-1$
								IClasspathEntry entry = JavaCore.newLibraryEntry(new Path(fileURL.getPath()), srcPath, Path.ROOT, ANNOTATION_ACCESS_RULES, DS_ATTRS, false);
								return Collections.singletonList(entry);
							}
						} catch (IOException e) {
							Platform.getLog(Activator.getContext().getBundle()).log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Error creating classpath entry.", e)); //$NON-NLS-1$
						}
					}
				}
			}
		}

		return Collections.emptyList();
	}

	public List<IClasspathEntry> getEntriesForDependency(BundleDescription project, BundleDescription addedDependency) {
		return Collections.emptyList();
	}
}
