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

	public static String DSAnnotationPreferenceListener_jobName;

	public static String DSAnnotationPreferenceListener_taskName;

	public static String DSAnnotationPropertyPage_enableCheckbox_text;

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
