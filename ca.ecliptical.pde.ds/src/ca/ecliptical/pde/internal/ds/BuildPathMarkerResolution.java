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
package ca.ecliptical.pde.internal.ds;

import org.eclipse.core.resources.IMarker;
import org.eclipse.pde.internal.ui.util.ModelModification;
import org.eclipse.pde.internal.ui.util.PDEModelUtility;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IMarkerResolution2;

@SuppressWarnings("restriction")
public abstract class BuildPathMarkerResolution implements IMarkerResolution2 {

	private final String label;

	private final String description;

	private final Image image;

	public BuildPathMarkerResolution(String label, String description, Image image) {
		this.label = label;
		this.description = description;
		this.image = image;
	}

	public String getLabel() {
		return label;
	}

	public String getDescription() {
		return description;
	}

	public Image getImage() {
		return image;
	}

	public void run(IMarker marker) {
		PDEModelUtility.modifyModel(createModification(marker), null);
	}

	protected abstract ModelModification createModification(IMarker marker);
}
