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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.ControlEnableState;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.dialogs.PropertyPage;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;
import org.eclipse.ui.preferences.IWorkingCopyManager;
import org.eclipse.ui.preferences.WorkingCopyManager;
import org.osgi.service.prefs.BackingStoreException;

public class DSAnnotationPropertyPage extends PropertyPage implements IWorkbenchPreferencePage {

	private Link workspaceLink;

	private Button projectCheckbox;

	private Control configBlockControl;

	private ControlEnableState configBlockEnableState;

	private Button enableCheckbox;

	private Text pathText;

	private Button classpathCheckbox;

	private Combo errorLevelCombo;

	private Combo missingUnbindMethodCombo;

	private IWorkingCopyManager wcManager;

	public void init(IWorkbench workbench) {
		// do nothing
	}

	@Override
	public void setContainer(IPreferencePageContainer container) {
		super.setContainer(container);

		if (wcManager == null) {
			if (container instanceof IWorkbenchPreferenceContainer) {
				wcManager = ((IWorkbenchPreferenceContainer) container).getWorkingCopyManager();
			} else {
				wcManager = new WorkingCopyManager();
			}
		}
	}

	@Override
	protected Label createDescriptionLabel(Composite parent) {
		if (isProjectPreferencePage()) {
			Composite composite = new Composite(parent, SWT.NONE);
			composite.setFont(parent.getFont());
			GridLayout layout = new GridLayout();
			layout.marginHeight = 0;
			layout.marginWidth = 0;
			layout.numColumns = 2;
			composite.setLayout(layout);
			composite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

			projectCheckbox = new Button(composite, SWT.CHECK);
			projectCheckbox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			projectCheckbox.setText(Messages.DSAnnotationPropertyPage_projectCheckbox_text);
			projectCheckbox.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					enableProjectSpecificSettings(projectCheckbox.getSelection());
					refreshWidgets();
				}
			});

			workspaceLink = createLink(composite, Messages.DSAnnotationPropertyPage_workspaceLink_text);
			workspaceLink.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));

			Label horizontalLine = new Label(composite, SWT.SEPARATOR | SWT.HORIZONTAL);
			horizontalLine.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false, 2, 1));
			horizontalLine.setFont(composite.getFont());
		}

		return super.createDescriptionLabel(parent);
	}

	private Link createLink(Composite composite, String text) {
		Link link = new Link(composite, SWT.NONE);
		link.setFont(composite.getFont());
		link.setText("<A>" + text + "</A>"); //$NON-NLS-1$ //$NON-NLS-2$
		link.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				if (PreferencesUtil.createPreferenceDialogOn(getShell(), Activator.PLUGIN_ID, new String[] { Activator.PLUGIN_ID }, null).open() == Window.OK)
					refreshWidgets();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});

		return link;
	}

	@Override
	protected Control createContents(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		composite.setLayout(layout);
		composite.setFont(parent.getFont());

		configBlockControl = createPreferenceContent(composite);
		configBlockControl.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));

		if (isProjectPreferencePage()) {
			boolean useProjectSettings = hasProjectSpecificOptions(getProject());
			enableProjectSpecificSettings(useProjectSettings);
		}

		refreshWidgets();

		Dialog.applyDialogFont(composite);
		return composite;
	}

	private Control createPreferenceContent(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(2, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		composite.setLayout(layout);
		composite.setFont(parent.getFont());

		enableCheckbox = new Button(composite, SWT.CHECK);
		enableCheckbox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		enableCheckbox.setText(Messages.DSAnnotationPropertyPage_enableCheckbox_text);

		Label pathLabel = new Label(composite, SWT.RIGHT);
		pathLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		pathLabel.setText(Messages.DSAnnotationPropertyPage_pathLabel_text);

		pathText = new Text(composite, SWT.BORDER | SWT.SINGLE);
		pathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		classpathCheckbox = new Button(composite, SWT.CHECK);
		classpathCheckbox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		classpathCheckbox.setText(Messages.DSAnnotationPropertyPage_classpathCheckbox_text);

		Label errorLevelLabel = new Label(composite, SWT.RIGHT);
		errorLevelLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		errorLevelLabel.setText(Messages.DSAnnotationPropertyPage_errorLevelLabel_text);

		errorLevelCombo = new Combo(composite, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
		errorLevelCombo.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
		errorLevelCombo.add(Messages.DSAnnotationPropertyPage_errorLevelError);
		errorLevelCombo.add(Messages.DSAnnotationPropertyPage_errorLevelWarning);
		errorLevelCombo.add(Messages.DSAnnotationPropertyPage_errorLevelNone);
		errorLevelCombo.select(0);

		Label missingUnbindMethodLabel = new Label(composite, SWT.RIGHT);
		missingUnbindMethodLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		missingUnbindMethodLabel.setText(Messages.DSAnnotationPropertyPage_missingUnbindMethodLevelLabel_text);

		missingUnbindMethodCombo = new Combo(composite, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
		missingUnbindMethodCombo.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
		missingUnbindMethodCombo.add(Messages.DSAnnotationPropertyPage_errorLevelError);
		missingUnbindMethodCombo.add(Messages.DSAnnotationPropertyPage_errorLevelWarning);
		missingUnbindMethodCombo.add(Messages.DSAnnotationPropertyPage_errorLevelNone);
		missingUnbindMethodCombo.select(0);

		Dialog.applyDialogFont(composite);
		return composite;
	}

	private void refreshWidgets() {
		IEclipsePreferences prefs = wcManager.getWorkingCopy(InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID));

		boolean enableValue = prefs.getBoolean(Activator.PREF_ENABLED, true);
		String pathValue = prefs.get(Activator.PREF_PATH, Activator.DEFAULT_PATH);
		boolean classpathValue = prefs.getBoolean(Activator.PREF_CLASSPATH, true);
		String errorLevel = prefs.get(Activator.PREF_VALIDATION_ERROR_LEVEL, ValidationErrorLevel.error.toString());
		String missingUnbindMethodLevel = prefs.get(Activator.PREF_MISSING_UNBIND_METHOD_ERROR_LEVEL, errorLevel);

		if (useProjectSettings()) {
			IScopeContext scopeContext = new ProjectScope(getProject());
			prefs = wcManager.getWorkingCopy(scopeContext.getNode(Activator.PLUGIN_ID));

			enableValue = prefs.getBoolean(Activator.PREF_ENABLED, enableValue);
			pathValue = prefs.get(Activator.PREF_PATH, pathValue);
			classpathValue = prefs.getBoolean(Activator.PREF_CLASSPATH, classpathValue);
			errorLevel = prefs.get(Activator.PREF_VALIDATION_ERROR_LEVEL, errorLevel);
			missingUnbindMethodLevel = prefs.get(Activator.PREF_MISSING_UNBIND_METHOD_ERROR_LEVEL, missingUnbindMethodLevel);
		}

		enableCheckbox.setSelection(enableValue);
		pathText.setText(pathValue);
		classpathCheckbox.setSelection(classpathValue);
		errorLevelCombo.select(getEnumIndex(errorLevel, ValidationErrorLevel.values(), 0));
		missingUnbindMethodCombo.select(getEnumIndex(missingUnbindMethodLevel, ValidationErrorLevel.values(), 0));

		setErrorMessage(null);
	}

	private <E extends Enum<E>> int getEnumIndex(String property, E[] values, int defaultIndex) {
		for (int i = 1; i < values.length; ++i) {
			if (property.equals(String.valueOf(values[i])))
				return i;
		}

		return defaultIndex;
	}

	private boolean hasProjectSpecificOptions(IProject project) {
		return new ProjectScope(project).getNode(Activator.PLUGIN_ID).get(Activator.PREF_ENABLED, null) != null;
	}

	private boolean useProjectSettings() {
		return isProjectPreferencePage() && projectCheckbox != null && projectCheckbox.getSelection();
	}

	private boolean isProjectPreferencePage() {
		return getElement() != null;
	}

	private void enableProjectSpecificSettings(boolean useProjectSpecificSettings) {
		projectCheckbox.setSelection(useProjectSpecificSettings);
		enablePreferenceContent(useProjectSpecificSettings);
		updateLinkVisibility();
	}

	private void enablePreferenceContent(boolean enable) {
		if (enable) {
			if (configBlockEnableState != null) {
				configBlockEnableState.restore();
				configBlockEnableState = null;
			}
		} else {
			if (configBlockEnableState == null) {
				configBlockEnableState = ControlEnableState.disable(configBlockControl);
			}
		}
	}

	private void updateLinkVisibility() {
		if (workspaceLink == null || workspaceLink.isDisposed())
			return;

		workspaceLink.setEnabled(!useProjectSettings());
	}

	private IProject getProject() {
		IAdaptable element = getElement();
		if (element == null)
			return null;

		if (element instanceof IProject)
			return (IProject) element;

		return (IProject) element.getAdapter(IProject.class);
	}

	@Override
	protected void performDefaults() {
		IScopeContext scopeContext;
		if (useProjectSettings()) {
			enableProjectSpecificSettings(false);
			scopeContext = new ProjectScope(getProject());
		} else {
			scopeContext = InstanceScope.INSTANCE;
		}

		IEclipsePreferences prefs = wcManager.getWorkingCopy(scopeContext.getNode(Activator.PLUGIN_ID));
		try {
			for (String key : prefs.keys()) {
				prefs.remove(key);
			}
		} catch (BackingStoreException e) {
			Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Unable to restore default values.", e)); //$NON-NLS-1$
		}

		refreshWidgets();
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		IEclipsePreferences prefs;
		if (isProjectPreferencePage()) {
			prefs = wcManager.getWorkingCopy(new ProjectScope(getProject()).getNode(Activator.PLUGIN_ID));
			if (!useProjectSettings()) {
				try {
					for (String key : prefs.keys()) {
						prefs.remove(key);
					}
				} catch (BackingStoreException e) {
					Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Unable to reset project preferences.", e)); //$NON-NLS-1$
				}

				prefs = null;
			}
		} else {
			prefs = wcManager.getWorkingCopy(InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID));
		}

		if (prefs != null) {
			String path = pathText.getText().trim();
			if (!Path.EMPTY.isValidPath(path)) {
				setErrorMessage(String.format(Messages.DSAnnotationPropertyPage_errorMessage_path));
				return false;
			}

			prefs.putBoolean(Activator.PREF_ENABLED, enableCheckbox.getSelection());
			prefs.put(Activator.PREF_PATH, new Path(path).toString());
			prefs.putBoolean(Activator.PREF_CLASSPATH, classpathCheckbox.getSelection());

			ValidationErrorLevel[] levels = ValidationErrorLevel.values();
			int errorLevelIndex = errorLevelCombo.getSelectionIndex();
			prefs.put(Activator.PREF_VALIDATION_ERROR_LEVEL, levels[Math.max(Math.min(errorLevelIndex, levels.length - 1), 0)].toString());

			errorLevelIndex = missingUnbindMethodCombo.getSelectionIndex();
			prefs.put(Activator.PREF_MISSING_UNBIND_METHOD_ERROR_LEVEL, levels[Math.max(Math.min(errorLevelIndex, levels.length - 1), 0)].toString());
		}

		try {
			wcManager.applyChanges();
		} catch (BackingStoreException e) {
			Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Unable to save preferences.", e)); //$NON-NLS-1$
			return false;
		}

		return true;
	}
}