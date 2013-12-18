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

import org.eclipse.osgi.util.NLS;

public class Messages extends NLS {

	private static final String BUNDLE_NAME = "ca.ecliptical.pde.internal.ds.messages"; //$NON-NLS-1$

	public static String DSAnnotationCompilationParticipant_duplicateLifeCycleMethodParameterType;

	public static String DSAnnotationCompilationParticipant_inconsistentComponentPropertyType;

	public static String DSAnnotationCompilationParticipant_invalidBindMethodParameters;

	public static String DSAnnotationCompilationParticipant_invalidBindMethodReturnType;

	public static String DSAnnotationCompilationParticipant_invalidComponentConfigurationPid;

	public static String DSAnnotationCompilationParticipant_invalidComponentDescriptorNamespace;

	public static String DSAnnotationCompilationParticipant_invalidComponentFactoryName;

	public static String DSAnnotationCompilationParticipant_invalidComponentImplementationClass;

	public static String DSAnnotationCompilationParticipant_invalidComponentName;

	public static String DSAnnotationCompilationParticipant_invalidComponentProperty_nameRequired;

	public static String DSAnnotationCompilationParticipant_invalidComponentProperty_valueRequired;

	public static String DSAnnotationCompilationParticipant_invalidComponentPropertyFile;

	public static String DSAnnotationCompilationParticipant_invalidComponentPropertyType;

	public static String DSAnnotationCompilationParticipant_invalidComponentPropertyValue;

	public static String DSAnnotationCompilationParticipant_invalidComponentService;

	public static String DSAnnotationCompilationParticipant_invalidLifeCycleMethodParameterType;

	public static String DSAnnotationCompilationParticipant_invalidLifeCycleMethodReturnType;

	public static String DSAnnotationCompilationParticipant_invalidReferenceService;

	public static String DSAnnotationCompilationParticipant_invalidReferenceUnbind;

	public static String DSAnnotationCompilationParticipant_invalidReferenceUpdated;

	public static String DSAnnotationCompilationParticipant_stringOrEmpty;

	public static String DSAnnotationCompilationParticipant_unknownServiceTypeLabel;

	public static String DSAnnotationPreferenceListener_jobName;

	public static String DSAnnotationPreferenceListener_taskName;

	public static String DSAnnotationPropertyPage_enableCheckbox_text;

	public static String DSAnnotationPropertyPage_errorLevelError;

	public static String DSAnnotationPropertyPage_errorLevelLabel_text;

	public static String DSAnnotationPropertyPage_errorLevelNone;

	public static String DSAnnotationPropertyPage_errorLevelWarning;

	public static String DSAnnotationPropertyPage_errorMessage_path;

	public static String DSAnnotationPropertyPage_pathLabel_text;

	public static String DSAnnotationPropertyPage_projectCheckbox_text;

	public static String DSAnnotationPropertyPage_workspaceLink_text;

	static {
		// initialize resource bundle
		NLS.initializeMessages(BUNDLE_NAME, Messages.class);
	}

	private Messages() {
		super();
	}
}
