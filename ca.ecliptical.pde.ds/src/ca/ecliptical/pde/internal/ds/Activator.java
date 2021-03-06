/*******************************************************************************
 * Copyright (c) 2012, 2015 Ecliptical Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Ecliptical Software Inc. - initial API and implementation
 *******************************************************************************/
package ca.ecliptical.pde.internal.ds;

import java.util.HashMap;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import ca.ecliptical.pde.ds.classpath.Constants;

public class Activator extends AbstractUIPlugin {

	// The plug-in ID
	public static final String PLUGIN_ID = "ca.ecliptical.pde.ds"; //$NON-NLS-1$

	public static final String PREF_ENABLED = "enabled"; //$NON-NLS-1$

	public static final String PREF_PATH = "path"; //$NON-NLS-1$

	public static final String PREF_CLASSPATH = Constants.PREF_CLASSPATH;

	public static final String PREF_VALIDATION_ERROR_LEVEL = "validationErrorLevel"; //$NON-NLS-1$

	public static final String PREF_MISSING_UNBIND_METHOD_ERROR_LEVEL = "validationErrorLevel.missingImplicitUnbindMethod"; //$NON-NLS-1$

	public static final String DEFAULT_PATH = "OSGI-INF"; //$NON-NLS-1$

	// The shared instance
	private static Activator plugin;

	private DSAnnotationPreferenceListener dsPrefListener;

	private final HashMap<IJavaProject, ProjectClasspathPreferenceChangeListener> projectPrefListeners = new HashMap<IJavaProject, ProjectClasspathPreferenceChangeListener>();

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.ui.plugin.AbstractUIPlugin#start(org.osgi.framework.BundleContext)
	 */
	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;

		dsPrefListener = new DSAnnotationPreferenceListener();
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.ui.plugin.AbstractUIPlugin#stop(org.osgi.framework.BundleContext)
	 */
	@Override
	public void stop(BundleContext context) throws Exception {
		dsPrefListener.dispose();

		synchronized (projectPrefListeners) {
			for (ProjectClasspathPreferenceChangeListener listener : projectPrefListeners.values()) {
				listener.dispose();
			}

			projectPrefListeners.clear();
		}

		plugin = null;
		super.stop(context);
	}

	/**
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}

	void listenForClasspathPreferenceChanges(IJavaProject project) {
		synchronized (projectPrefListeners) {
			if (!projectPrefListeners.containsKey(project))
				projectPrefListeners.put(project, new ProjectClasspathPreferenceChangeListener(project));
		}
	}

	void disposeProjectClasspathPreferenceChangeListener(IJavaProject project) {
		synchronized (projectPrefListeners) {
			ProjectClasspathPreferenceChangeListener listener = projectPrefListeners.remove(project);
			if (listener != null)
				listener.dispose();
		}
	}

	public static void log(IStatus status) {
		getDefault().getLog().log(status);
	}

	public static void log(Throwable e) {
		log(new Status(IStatus.ERROR, PLUGIN_ID, IStatus.OK, e.getLocalizedMessage(), e));
	}
}
