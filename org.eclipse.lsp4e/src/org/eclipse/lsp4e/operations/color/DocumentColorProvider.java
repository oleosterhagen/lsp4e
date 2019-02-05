/**
 *  Copyright (c) 2018 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - [code mining] Support 'textDocument/documentColor' with CodeMining - Bug 533322
 */
package org.eclipse.lsp4e.operations.color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4j.DocumentColorParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGBA;
import org.eclipse.swt.widgets.Display;

/**
 * Consume the 'textDocument/documentColor' request to decorate color references
 * in the editor.
 *
 */
public class DocumentColorProvider extends AbstractCodeMiningProvider {

	private final Map<RGBA, Color> colorTable;

	public DocumentColorProvider() {
		colorTable = new HashMap<>();
	}

	private CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(@NonNull IDocument document) {
		TextDocumentIdentifier textDocumentIdentifier = new TextDocumentIdentifier(
				LSPEclipseUtils.toUri(document).toString());
		DocumentColorParams param = new DocumentColorParams(textDocumentIdentifier);
		final List<ColorInformationMining> colorResults = Collections.synchronizedList(new ArrayList<>());
		return LanguageServiceAccessor.getLanguageServers(document, DocumentColorProvider::isColorProvider)
				.thenApplyAsync(languageServers -> {
					return CompletableFuture.allOf(languageServers.stream().map(languageServer -> languageServer
							.getTextDocumentService().documentColor(param)
							.thenAccept(colors -> colors.stream().filter(Objects::nonNull)
									.map(color -> {
										try {
											return new ColorInformationMining(color, document, textDocumentIdentifier,
													languageServer, DocumentColorProvider.this);
										} catch (BadLocationException e) {
											LanguageServerPlugin.logError(e);
											return null;
										}
									}).filter(Objects::nonNull)
									.forEach(colorResults::add)))
							.toArray(CompletableFuture[]::new));
				}).thenApply(theVoid -> colorResults);
	}

	@Override
	public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer,
			IProgressMonitor monitor) {
		return provideCodeMinings(viewer.getDocument());
	}

	/**
	 * Returns the color from the given rgba.
	 *
	 * @param rgba
	 *                    the rgba declaration
	 * @param display
	 *                    the display to use to create a color instance
	 * @return the color from the given rgba.
	 */
	public Color getColor(RGBA rgba, Display display) {
		Color color = colorTable.get(rgba);
		if (color == null) {
			color = new Color(display, rgba);
			colorTable.put(rgba, color);
		}
		return color;
	}

	@Override
	public void dispose() {
		colorTable.values().forEach(color -> color.dispose());
		super.dispose();
	}

	private static boolean isColorProvider(ServerCapabilities capabilities) {
		return capabilities.getColorProvider() != null
				&& ((capabilities.getColorProvider().getLeft() != null && capabilities.getColorProvider().getLeft())
						|| capabilities.getColorProvider().getRight() != null);
	}

}
