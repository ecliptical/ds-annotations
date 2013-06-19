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

import java.util.ArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.PlatformUI;

public class DSAnnotationPreferenceListener implements IPreferenceChangeListener {

	public DSAnnotationPreferenceListener() {
		InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).addPreferenceChangeListener(this);
	}

	public void preferenceChange(PreferenceChangeEvent event) {
		final IWorkspace ws = ResourcesPlugin.getWorkspace();
		if (!ws.isAutoBuilding())
			return;

		Job job = new Job(Messages.DSAnnotationPreferenceListener_jobName) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					ws.run(new IWorkspaceRunnable() {
						public void run(IProgressMonitor monitor) throws CoreException {
							IProject[] projects = ws.getRoot().getProjects();
							ArrayList<IProject> managedProjects = new ArrayList<IProject>(projects.length);

							for (IProject project : projects) {
								if (project.isOpen() && DSAnnotationCompilationParticipant.isManaged(project))
									managedProjects.add(project);
							}

							if (monitor != null)
								monitor.beginTask(Messages.DSAnnotationPreferenceListener_taskName, managedProjects.size());

							try {
								for (IProject project : managedProjects)
									project.build(IncrementalProjectBuilder.FULL_BUILD, new SubProgressMonitor(monitor, 1));
							} finally {
								if (monitor != null)
									monitor.done();
							}
						}
					}, monitor);
				} catch (CoreException e) {
					return e.getStatus();
				}

				return Status.OK_STATUS;
			};
		};

		PlatformUI.getWorkbench().getProgressService().showInDialog(null, job);
		job.schedule();
	}

	public void dispose() {
		InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).removePreferenceChangeListener(this);
	}
}
