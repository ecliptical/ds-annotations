/*******************************************************************************
 * Copyright (c) 2013 Ecliptical Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ecliptical Software Inc. - initial API and implementation
 *******************************************************************************/
package ca.ecliptical.pde.internal.ds;

import org.eclipse.jdt.core.compiler.CategorizedProblem;

public class DSAnnotationProblem extends CategorizedProblem {

	private final boolean error;

	private final String message;

	private final String[] args;

	private char[] filename;

	private int sourceStart;

	private int sourceEnd;

	private int sourceLineNumber;

	public DSAnnotationProblem(boolean error, String message, String... args) {
		this.error = error;
		this.message = message;
		this.args = args;
	}

	public boolean isError() {
		return error;
	}

	public boolean isWarning() {
		return !error;
	}

	public int getID() {
		return 0;
	}

	@Override
	public int getCategoryID() {
		return CAT_POTENTIAL_PROGRAMMING_PROBLEM;
	}

	@Override
	public String getMarkerType() {
		return "ca.ecliptical.pde.ds.problem"; //$NON-NLS-1$
	}

	public char[] getOriginatingFileName() {
		return filename;
	}

	public void setOriginatingFileName(char[] filename) {
		this.filename = filename;
	}

	public String[] getArguments() {
		return args;
	}

	public String getMessage() {
		return message;
	}

	public int getSourceStart() {
		return sourceStart;
	}

	public void setSourceStart(int sourceStart) {
		this.sourceStart = sourceStart;
	}

	public int getSourceEnd() {
		return sourceEnd;
	}

	public void setSourceEnd(int sourceEnd) {
		this.sourceEnd = sourceEnd;
	}

	public int getSourceLineNumber() {
		return sourceLineNumber;
	}

	public void setSourceLineNumber(int sourceLineNumber) {
		this.sourceLineNumber = sourceLineNumber;
	}
}
