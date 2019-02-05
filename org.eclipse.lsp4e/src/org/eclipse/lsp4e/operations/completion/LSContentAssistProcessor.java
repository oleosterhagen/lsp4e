/*******************************************************************************
 * Copyright (c) 2016, 2017 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Lucas Bullen (Red Hat Inc.) - Bug 520700 - TextEditors within FormEditors are not supported *   Lucas Bullen (Red Hat Inc.) - Refactored for incomplete completion lists
 *								- Refactored for incomplete completion lists
 *******************************************************************************/
package org.eclipse.lsp4e.operations.completion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ContextInformation;
import org.eclipse.jface.text.contentassist.ContextInformationValidator;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.common.base.Strings;

public class LSContentAssistProcessor implements IContentAssistProcessor {

	private static final long TRIGGERS_TIMEOUT = 50;
	private static final long COMPLETION_TIMEOUT = 1000;
	private IDocument currentDocument;
	private String errorMessage;
	private CompletableFuture<List<@NonNull LanguageServer>> completionLanguageServersFuture;
	private final Object completionTriggerCharsSemaphore = new Object();
	private char[] completionTriggerChars = new char[0];
	private CompletableFuture<List<@NonNull LanguageServer>> contextInformationLanguageServersFuture;
	private final Object contextTriggerCharsSemaphore = new Object();
	private char[] contextTriggerChars = new char[0];

	public LSContentAssistProcessor() {
	}

	private Comparator<LSCompletionProposal> proposalConparoator = new LSCompletionProposalComparator();

	@Override
	public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
		IDocument document = viewer.getDocument();
		initiateLanguageServers(document);
		CompletionParams param;
		try {
			param = LSPEclipseUtils.toCompletionParams(LSPEclipseUtils.toUri(document), offset, document);
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return new ICompletionProposal[] { createErrorProposal(offset, e) };
		}
		List<ICompletionProposal> proposals = Collections.synchronizedList(new ArrayList<>());
		try {
			this.completionLanguageServersFuture
					.thenComposeAsync(languageServers -> CompletableFuture.allOf(languageServers.stream()
							.map(languageServer -> languageServer.getTextDocumentService().completion(param)
									.thenAccept(completion -> proposals
											.addAll(toProposals(document, offset, completion, languageServer))))
							.toArray(CompletableFuture[]::new)))
					.get(COMPLETION_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			LanguageServerPlugin.logError(e);
			return new ICompletionProposal[] { createErrorProposal(offset, e) };
		}
		LSCompletionProposal[] completeProposals = new LSCompletionProposal[proposals.size()];
		int i = 0;
		for (ICompletionProposal proposal : proposals) {
			if (proposal instanceof LSCompletionProposal) {
				completeProposals[i] = (LSCompletionProposal) proposal;
				i++;
			} else {
				return proposals.toArray(new ICompletionProposal[proposals.size()]);
			}
		}
		Arrays.sort(completeProposals, proposalConparoator);
		return completeProposals;
	}

	private CompletionProposal createErrorProposal(int offset, Exception ex) {
		return new CompletionProposal("", offset, 0, offset, null, Messages.completionError, null, ex.getMessage()); //$NON-NLS-1$
	}

	private void initiateLanguageServers(@NonNull IDocument document) {
		if (currentDocument != document) {
			this.currentDocument = document;
			if (this.completionLanguageServersFuture != null) {
				try {
					this.completionLanguageServersFuture.cancel(true);
				} catch (CancellationException ex) {
					// nothing
				}
			}
			if (this.contextInformationLanguageServersFuture != null) {
				try {
					this.contextInformationLanguageServersFuture.cancel(true);
				} catch (CancellationException ex) {
					// nothing
				}
			}
			this.completionTriggerChars = new char[0];
			this.contextTriggerChars = new char[0];

			this.completionLanguageServersFuture = LanguageServiceAccessor.getLanguageServers(document,
					capabilities -> {
						CompletionOptions provider = capabilities.getCompletionProvider();
						if (provider != null) {
							synchronized (this.completionTriggerCharsSemaphore) {
								this.completionTriggerChars = mergeTriggers(this.completionTriggerChars,
										provider.getTriggerCharacters());
							}
							return true;
						}
						return false;
					});
			this.contextInformationLanguageServersFuture = LanguageServiceAccessor.getLanguageServers(document,
					capabilities -> {
						SignatureHelpOptions provider = capabilities.getSignatureHelpProvider();
						if (provider != null) {
							synchronized (this.contextTriggerCharsSemaphore) {
								this.contextTriggerChars = mergeTriggers(this.contextTriggerChars,
										provider.getTriggerCharacters());
							}
							return true;
						}
						return false;
					});
		}

	}

	private static List<ICompletionProposal> toProposals(IDocument document,
			int offset, Either<List<CompletionItem>, CompletionList> completionList, LanguageServer languageServer) {
		if (completionList == null) {
			return Collections.emptyList();
		}
		List<CompletionItem> items = Collections.emptyList();
		boolean isIncomplete = false;
		if (completionList.isLeft()) {
			items = completionList.getLeft();
		} else if (completionList.isRight()) {
			isIncomplete = completionList.getRight().isIncomplete();
			items = completionList.getRight().getItems();
		}
		List<ICompletionProposal> proposals = new ArrayList<>();
		for (CompletionItem item : items) {
			if (item != null) {
				if (isIncomplete) {
					ICompletionProposal proposal = new LSIncompleteCompletionProposal(document, offset, item,
							languageServer);
					proposals.add(proposal);
				} else {
					ICompletionProposal proposal = new LSCompletionProposal(document, offset, item, languageServer);
					if (((LSCompletionProposal) proposal).validate(document, offset, null)) {
						proposals.add(proposal);
					}
				}
			}
		}
		return proposals;
	}

	@Override
	public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset) {
		initiateLanguageServers(viewer.getDocument());
		TextDocumentPositionParams param;
		try {
			param = LSPEclipseUtils.toTextDocumentPosistionParams(offset, viewer.getDocument());
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return new IContextInformation[] { /* TODO? show error in context information */ };
		}
		List<IContextInformation> contextInformations = Collections.synchronizedList(new ArrayList<>());
		try {
			contextInformationLanguageServersFuture
					.thenComposeAsync(languageServers -> CompletableFuture.allOf(languageServers.stream()
							.map(languageServer -> languageServer.getTextDocumentService().signatureHelp(param))
							.map(signatureHelpFuture -> signatureHelpFuture.thenAccept(signatureHelp -> signatureHelp
									.getSignatures().stream().map(LSContentAssistProcessor::toContextInformation)
									.forEach(contextInformations::add)))
							.toArray(CompletableFuture[]::new)))
					.get(COMPLETION_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			LanguageServerPlugin.logError(e);
			return new IContextInformation[] { /* TODO? show error in context information */ };
		}
		return contextInformations.toArray(new IContextInformation[0]);
	}

	private static IContextInformation toContextInformation(SignatureInformation information) {
		StringBuilder signature = new StringBuilder(information.getLabel());
		String docString = LSPEclipseUtils.getDocString(information.getDocumentation());
		if (docString!=null && !docString.isEmpty()) {
			signature.append('\n').append(docString);
		}
		IContextInformation contextInformation = new ContextInformation(
				information.getLabel(), signature.toString());
		return contextInformation;
	}

	@Override
	public char[] getCompletionProposalAutoActivationCharacters() {
		ITextEditor textEditor = LSPEclipseUtils.getActiveTextEditor();
		if(textEditor != null) {
			initiateLanguageServers(LSPEclipseUtils.getDocument(textEditor));
			try {
				this.completionLanguageServersFuture.get(TRIGGERS_TIMEOUT, TimeUnit.MILLISECONDS);
			} catch (InterruptedException | OperationCanceledException | TimeoutException | ExecutionException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		return completionTriggerChars;
	}

	private static char[] mergeTriggers(char[] initialArray, Collection<String> additionalTriggers) {
		if (initialArray == null) {
			initialArray = new char[0];
		}
		if (additionalTriggers == null) {
			additionalTriggers = Collections.emptySet();
		}
		Set<Character> triggers = new HashSet<>();
		for (char c : initialArray) {
			triggers.add(Character.valueOf(c));
		}
		additionalTriggers.stream().filter(s -> !Strings.isNullOrEmpty(s))
				.map(triggerChar -> Character.valueOf(triggerChar.charAt(0))).forEach(triggers::add);
		char[] res = new char[triggers.size()];
		int i = 0;
		for (Character c : triggers) {
			res[i] = c.charValue();
			i++;
		}
		return res;
	}

	@Override
	public char[] getContextInformationAutoActivationCharacters() {
		ITextEditor textEditor = LSPEclipseUtils.getActiveTextEditor();
		if(textEditor != null) {
			initiateLanguageServers(LSPEclipseUtils.getDocument(textEditor));
			try {
				this.contextInformationLanguageServersFuture.get(TRIGGERS_TIMEOUT, TimeUnit.MILLISECONDS);
			} catch (InterruptedException | OperationCanceledException | TimeoutException | ExecutionException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		return contextTriggerChars;
	}

	@Override
	public String getErrorMessage() {
		return this.errorMessage;
	}

	@Override
	public IContextInformationValidator getContextInformationValidator() {
		return new ContextInformationValidator(this);
	}
}
