/*******************************************************************************
 * Copyright (c) 2017 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - Initial implementation
 *******************************************************************************/
package org.eclipse.acute.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.AbstractDocument;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.tests.harness.util.DisplayHelper;
import org.junit.Test;

public class TestLSPIntegration extends AbstractAcuteTest {

	@Override
	public void setUp() throws Exception {
		super.setUp();
		LanguageServerPlugin.getDefault().getPreferenceStore().putValue("org.eclipse.acute.Omnisharp.file.logging.enabled", Boolean.toString(true));
	}

	@Test
	public void testLSFoundWithCSProj() throws Exception {
		IProject project = getProject("csproj");
		IFile csharpSourceFile = project.getFile("Program.cs");
		IEditorPart editor = IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), csharpSourceFile);
		SourceViewer viewer = (SourceViewer)getTextViewer(editor);
		workaroundOmniSharpIssue1088(viewer.getDocument());
		int offset = viewer.getDocument().get().indexOf("WriteLine") + 6;
		viewer.setSelectedRange(offset, 0);
		AtomicReference<ICompletionProposal> topProposal = new AtomicReference<>();
		viewer.getContentAssistantFacade().addCompletionListener(new ICompletionListener() {
			@Override public void selectionChanged(ICompletionProposal proposal, boolean smartToggle) {
				topProposal.set(proposal);
			}

			@Override public void assistSessionStarted(ContentAssistEvent event) {
				// nothing
			}

			@Override public void assistSessionEnded(ContentAssistEvent event) {
				// nothing
			}
		});
		viewer.doOperation(SourceViewer.CONTENTASSIST_PROPOSALS);
		assertTrue(new DisplayHelper() {
			@Override protected boolean condition() {
				ICompletionProposal proposal = topProposal.get();
				return proposal != null && proposal.getDisplayString().contains("WriteLine");
			}
		}.waitForCondition(viewer.getTextWidget().getDisplay(), 5000));
	}

	private void workaroundOmniSharpIssue1088(IDocument document) throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		// Wait for document to be connected
		Method getDocumentListenersMethod = AbstractDocument.class.getDeclaredMethod("getDocumentListeners");
		getDocumentListenersMethod.setAccessible(true);
		new DisplayHelper() {
			@Override protected boolean condition() {
				try {
					return ((Collection<?>)getDocumentListenersMethod.invoke(document)).stream().anyMatch(o -> o.getClass().getName().contains("lsp4e"));
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					e.printStackTrace();
					return false;
				}
			}
		}.waitForCondition(Display.getDefault(), 5000);
		assertNotEquals("LS Document listener was not setup after 5s", Collections.emptyList(), getDocumentListenersMethod.invoke(document));
		// workaround https://github.com/OmniSharp/omnisharp-roslyn/issues/1445
		DisplayHelper.sleep(5000);
		// force fake modification for OmniSharp to wake up
		document.set(document.get().replace("Hello", "Kikoo"));
		DisplayHelper.sleep(500);
	}

	@Test
	public void testLSFindsDiagnosticsCSProj() throws Exception  {
		IProject project = getProject("csprojWithError");
		IFile csharpSourceFile = project.getFile("Program.cs");
		IEditorPart editor = IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), csharpSourceFile);
		SourceViewer viewer = (SourceViewer)getTextViewer(editor);
		workaroundOmniSharpIssue1088(viewer.getDocument());
		new DisplayHelper() {
			@Override
			protected boolean condition() {
				try {
					return csharpSourceFile.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO).length > 0;
				} catch (Exception e) {
					return false;
				}
			}
		}.waitForCondition(Display.getDefault(), 5000);
		DisplayHelper.sleep(500); // time to fill marker details
		IMarker marker = csharpSourceFile.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)[0];
		assertTrue(marker.getType().contains("lsp4e"));
		assertEquals(12, marker.getAttribute(IMarker.LINE_NUMBER, -1));
	}
}