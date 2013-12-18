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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class ProjectContext {

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