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

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class ProjectState implements Serializable {

	private static final long serialVersionUID = 8616641822921441882L;

	// classpath-relative CU path (portable) to plugin-root-relative (portable) paths of generated DS files
	private final Map<String, Collection<String>> mappings = new HashMap<String, Collection<String>>();

	private String path;

	private ValidationErrorLevel errorLevel;

	public Map<String, Collection<String>> getMappings() {
		return mappings;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public ValidationErrorLevel getErrorLevel() {
		return errorLevel == null ?  ValidationErrorLevel.error : errorLevel;
	}

	public void setErrorLevel(ValidationErrorLevel errorLevel) {
		this.errorLevel = errorLevel;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this)
			return true;

		if (obj == null || !getClass().equals(obj.getClass()))
			return false;

		ProjectState o = (ProjectState) obj;
		return (path == null ? o.path == null : path.equals(o.path))
				&& errorLevel == o.errorLevel
				&& mappings.equals(o.mappings);
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder("ProjectState[path="); //$NON-NLS-1$
		buf.append(path).append(";mappings="); //$NON-NLS-1$
		buf.append(mappings).append(";errorLevel="); //$NON-NLS-1$
		buf.append(errorLevel).append(']');
		return buf.toString();
	}
}
