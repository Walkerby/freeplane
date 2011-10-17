/*
 *  Freeplane - mind map editor
 *  Copyright (C) 2008 Joerg Mueller, Daniel Polansky, Christian Foltin, Dimitry Polivaev
 *
 *  This file is created by Dimitry Polivaev in 2008.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.freeplane.features.clipboard.mindmapmode;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import org.apache.commons.lang.StringUtils;
import org.freeplane.core.resources.ResourceController;
import org.freeplane.core.ui.components.UITools;
import org.freeplane.core.util.FixedHTMLWriter;
import org.freeplane.core.util.HtmlUtils;
import org.freeplane.core.util.LogUtils;
import org.freeplane.core.util.TextUtils;
import org.freeplane.features.attribute.AttributeController;
import org.freeplane.features.clipboard.ClipboardController;
import org.freeplane.features.clipboard.MindMapNodesSelection;
import org.freeplane.features.link.LinkController;
import org.freeplane.features.link.mindmapmode.MLinkController;
import org.freeplane.features.map.FreeNode;
import org.freeplane.features.map.MapModel;
import org.freeplane.features.map.MapReader;
import org.freeplane.features.map.NodeModel;
import org.freeplane.features.map.MapReader.NodeTreeCreator;
import org.freeplane.features.map.MapWriter.Hint;
import org.freeplane.features.map.MapWriter.Mode;
import org.freeplane.features.map.mindmapmode.MMapController;
import org.freeplane.features.mode.Controller;
import org.freeplane.features.mode.ModeController;
import org.freeplane.features.text.TextController;
import org.freeplane.features.text.mindmapmode.MTextController;
import org.freeplane.n3.nanoxml.XMLException;
import org.freeplane.view.swing.features.filepreview.ExternalResource;

/**
 * @author Dimitry Polivaev
 */
public class MClipboardController extends ClipboardController {
	public static final String RESOURCES_REMIND_USE_RICH_TEXT_IN_NEW_NODES = "remind_use_rich_text_in_new_nodes";
	private class DirectHtmlFlavorHandler implements IDataFlavorHandler {
		private String textFromClipboard;

		public DirectHtmlFlavorHandler(final String textFromClipboard) {
			this.textFromClipboard = textFromClipboard;
		}

		void paste(final NodeModel target) {
			textFromClipboard = cleanHtml(textFromClipboard);
			final NodeModel node = Controller.getCurrentModeController().getMapController().newNode(textFromClipboard,
					Controller.getCurrentController().getMap());
			final String text = textFromClipboard;
			final Matcher m = HREF_PATTERN.matcher(text);
			if (m.matches()) {
				final String body = m.group(2);
				if (!body.matches(".*<\\s*a.*")) {
					final String href = m.group(1);					
					((MLinkController) LinkController.getController()).setLinkTypeDependantLink(node, href);
				}
			}
			((MMapController) Controller.getCurrentModeController().getMapController()).insertNode(node, target);
		}

		public void paste(final NodeModel target, final boolean asSibling, final boolean isLeft) {
			paste(target);
		}
	}

	private class FileListFlavorHandler implements IDataFlavorHandler {
		final List<File> fileList;

		public FileListFlavorHandler(final List<File> fileList) {
			super();
			this.fileList = fileList;
		}

		public void paste(final NodeModel target, final boolean asSibling, final boolean isLeft) {
			for (final File file : fileList) {
				final MMapController mapController = (MMapController) Controller.getCurrentModeController().getMapController();
				final NodeModel node = mapController.newNode(file.getName(), target.getMap());				
				((MLinkController) LinkController.getController()).setLinkTypeDependantLink(node, file);
				mapController.insertNode(node, target, asSibling, isLeft, isLeft);
			}
		}
	}

	interface IDataFlavorHandler {
		void paste(NodeModel target, boolean asSibling, boolean isLeft);
	}

	private class MindMapNodesFlavorHandler implements IDataFlavorHandler {
		private final String textFromClipboard;

		public MindMapNodesFlavorHandler(final String textFromClipboard) {
			this.textFromClipboard = textFromClipboard;
		}

		public void paste(final NodeModel target, final boolean asSibling, final boolean isLeft) {
			if (textFromClipboard != null) {
				paste(textFromClipboard, target, asSibling, isLeft);
			}
		}

		private void paste(final String text, final NodeModel target, final boolean asSibling, final boolean isLeft) {
			final String[] textLines = text.split(ClipboardController.NODESEPARATOR);
			final MMapController mapController = (MMapController) Controller.getCurrentModeController().getMapController();
			final MapReader mapReader = mapController.getMapReader();
			final NodeTreeCreator nodeTreeCreator = mapReader.nodeTreeCreator(target.getMap());
			nodeTreeCreator.setHint(Hint.MODE, Mode.CLIPBOARD);
			for (int i = 0; i < textLines.length; ++i) {
				try {
					final NodeModel newModel = nodeTreeCreator.create(new StringReader(textLines[i]));
					newModel.removeExtension(FreeNode.class);
					final boolean wasLeft = newModel.isLeft();
					mapController.insertNode(newModel, target, asSibling, isLeft, wasLeft != isLeft);
				}
				catch (final XMLException e) {
					LogUtils.severe("error on paste", e);
				}
			}
			nodeTreeCreator.finish(target);
		}
	}

	private static class PasteHtmlWriter extends FixedHTMLWriter {
		private final Element element;

		public PasteHtmlWriter(final Writer writer, final Element element, final HTMLDocument doc, final int pos,
		                       final int len) {
			super(writer, doc, pos, len);
			this.element = getStandAloneElement(element);
		}

		@Override
		protected ElementIterator getElementIterator() {
			return new ElementIterator(element);
		}

		private Element getStandAloneElement(final Element element) {
			final String name = element.getName();
			if (name.equals("ul") || name.equals("ol") || name.equals("table") || name.equals("html")) {
				return element;
			}
			return getStandAloneElement(element.getParentElement());
		}

		@Override
		public void write() throws IOException, BadLocationException {
			if (element.getName().equals("html")) {
				super.write();
				return;
			}
			write("<html>");
			super.write();
			write("</html>");
		}
	}

	private class StringFlavorHandler implements IDataFlavorHandler {
		private final String textFromClipboard;

		public StringFlavorHandler(final String textFromClipboard) {
			this.textFromClipboard = textFromClipboard;
		}

		public void paste(final NodeModel target, final boolean asSibling, final boolean isLeft) {
			final TextFragment[] textFragments = split(textFromClipboard);
			pasteStringWithoutRedisplay(textFragments, target, asSibling, isLeft);
		}

		private TextFragment[] split(final String textFromClipboard) {
			final LinkedList<TextFragment> textFragments = new LinkedList<TextFragment>();
			final String[] textLines = textFromClipboard.split("\n");
			for (int i = 0; i < textLines.length; ++i) {
				String text = textLines[i];
				text = text.replaceAll("\t", "        ");
				if (text.matches(" *")) {
					continue;
				}
				int depth = 0;
				while (depth < text.length() && text.charAt(depth) == ' ') {
					++depth;
				}
				final String visibleText = text.trim();
				final String link = LinkController.findLink(text);
				if (!visibleText.equals("")) {
					textFragments.add(new TextFragment(visibleText, link, depth));
				}
			}
			return textFragments.toArray(new TextFragment[textFragments.size()]);
		}
	}

	private class StructuredHtmlFlavorHandler implements IDataFlavorHandler {
		private final String textFromClipboard;

		public StructuredHtmlFlavorHandler(final String textFromClipboard) {
			this.textFromClipboard = textFromClipboard;
		}

		private String addFragment(final HTMLDocument doc, final Element element, final int depth, final int start,
		                           final int end, final LinkedList<TextFragment> htmlFragments)
		        throws BadLocationException, IOException {
			final String paragraphText = doc.getText(start, end - start).trim();
			if (paragraphText.length() > 0) {
				final StringWriter out = new StringWriter();
				new PasteHtmlWriter(out, element, doc, start, end - start).write();
				final String string = out.toString();
				if (!string.equals("")) {
					final String link = LinkController.findLink(string);
					final TextFragment htmlFragment = new TextFragment(string, link, depth);
					htmlFragments.add(htmlFragment);
				}
			}
			return paragraphText;
		}

		private Element getParentElement(final HTMLDocument doc) {
			final Element htmlRoot = doc.getDefaultRootElement();
			final Element bodyElement = htmlRoot.getElement(htmlRoot.getElementCount() - 1);
			Element parentCandidate = bodyElement;
			do {
				if (parentCandidate.getElementCount() > 1) {
					return parentCandidate;
				}
				parentCandidate = parentCandidate.getElement(0);
			} while (!(parentCandidate.isLeaf() || parentCandidate.getName().equalsIgnoreCase("p-implied")));
			return bodyElement;
		}

		private boolean isSeparateElement(final Element current) {
			return !current.isLeaf();
		}

		public void paste(final NodeModel target, final boolean asSibling, final boolean isLeft) {
			pasteHtmlWithoutRedisplay(textFromClipboard, target, asSibling, isLeft);
		}

		private void pasteHtmlWithoutRedisplay(final Object t, final NodeModel parent, final boolean asSibling,
		                                       final boolean isLeft) {
			String textFromClipboard = (String) t;
			textFromClipboard = cleanHtml(textFromClipboard);
			final TextFragment[] htmlFragments = split(textFromClipboard);
			pasteStringWithoutRedisplay(htmlFragments, parent, asSibling, isLeft);
		}

		private void split(final HTMLDocument doc, final Element parent, final LinkedList<TextFragment> htmlFragments,
		                   int depth) throws BadLocationException, IOException {
			final int elementCount = parent.getElementCount();
			int headerDepth = 0;
			boolean headerFound = false;
			int start = -1;
			int end = -1;
			Element last = null;
			for (int i = 0; i < elementCount; i++) {
				final Element current = parent.getElement(i);
				final String name = current.getName();
				final Matcher matcher = HEADER_REGEX.matcher(name);
				if (matcher.matches()) {
					try {
						if (!headerFound) {
							depth--;
						}
						final int newHeaderDepth = Integer.parseInt(matcher.group(1));
						depth += newHeaderDepth - headerDepth;
						headerDepth = newHeaderDepth;
						headerFound = true;
					}
					catch (final NumberFormatException e) {
						LogUtils.severe(e);
					}
				}
				else {
					if (headerFound) {
						headerFound = false;
						depth++;
					}
				}
				final boolean separateElement = isSeparateElement(current);
				if (separateElement && current.getElementCount() != 0) {
					start = -1;
					last = null;
					split(doc, current, htmlFragments, depth + 1);
					continue;
				}
				if (separateElement && start != -1) {
					addFragment(doc, last, depth, start, end, htmlFragments);
				}
				if (start == -1 || separateElement) {
					start = current.getStartOffset();
					last = current;
				}
				end = current.getEndOffset();
				if (separateElement) {
					addFragment(doc, current, depth, start, end, htmlFragments);
				}
			}
			if (start != -1) {
				addFragment(doc, last, depth, start, end, htmlFragments);
			}
		}

		private TextFragment[] split(final String text) {
			final LinkedList<TextFragment> htmlFragments = new LinkedList<TextFragment>();
			final HTMLEditorKit kit = new HTMLEditorKit();
			final HTMLDocument doc = new HTMLDocument();
			final StringReader buf = new StringReader(text);
			try {
				kit.read(buf, doc, 0);
				final Element parent = getParentElement(doc);
				split(doc, parent, htmlFragments, 0);
			}
			catch (final IOException e) {
				LogUtils.severe(e);
			}
			catch (final BadLocationException e) {
				LogUtils.severe(e);
			}
			return htmlFragments.toArray(new TextFragment[htmlFragments.size()]);
		}
	}

	private static class TextFragment {
		int depth;
		String link;
		String text;

		public TextFragment(final String text, final String link, final int depth) {
			super();
			this.text = text;
			this.depth = depth;
			this.link = link;
		}
	}

    private class ImageFlavorHandler implements IDataFlavorHandler {
    	final private BufferedImage image;

        public ImageFlavorHandler(BufferedImage image) {
	        super();
	        this.image = image;
        }

        public void paste(NodeModel target, boolean asSibling, boolean isLeft) {
			final ModeController modeController = Controller.getCurrentModeController();
			final MMapController mapController = (MMapController) modeController.getMapController();
            File mindmapFile = target.getMap().getFile();
            if(mindmapFile == null) {
                UITools.errorMessage(TextUtils.getRawText("map_not_saved"));
            }
			final String mmFileName = mindmapFile.getName();
			final String fileNameTemplate = mmFileName.substring(0, mmFileName.lastIndexOf('.')) + "_";
			//file that we'll save to disk.
            File file;
            try {
	            File tempFile = File.createTempFile(fileNameTemplate, ".jpg", mindmapFile.getParentFile());
	            String imgfilepath=tempFile.getAbsolutePath();
	            file = new File(imgfilepath);
	            ImageIO.write(image, "jpg", file);
				final NodeModel node = mapController.newNode(file.getName(), target.getMap());
				final ExternalResource extension = new ExternalResource();
				extension.setUri(file.toURI());
				node.addExtension(extension);
				mapController.insertNode(node, target, asSibling, isLeft, isLeft);
            }
            catch (IOException e) {
	            e.printStackTrace();
            }
        }
    }
	private static final Pattern HEADER_REGEX = Pattern.compile("h(\\d)", Pattern.CASE_INSENSITIVE);
	private static final Pattern HREF_PATTERN = Pattern
	    .compile("<html>\\s*<body>\\s*<a\\s+href=\"([^>]+)\">(.*)</a>\\s*</body>\\s*</html>");
	private static final String RESOURCE_UNFOLD_ON_PASTE = "unfold_on_paste";
	public static final String RESOURCES_CUT_NODES_WITHOUT_QUESTION = "cut_nodes_without_question";
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public static String firstLetterCapitalized(final String text) {
		if (text == null || text.length() == 0) {
			return text;
		}
		return text.substring(0, 1).toUpperCase() + text.substring(1, text.length());
	}

	private List<NodeModel> newNodes;

	/**
	 * @param modeController
	 */
	public MClipboardController() {
		super();
		createActions();
	}

	private String cleanHtml(String in) {
		in = in.replaceFirst("(?i)(?s)<head>.*</head>", "").replaceFirst("(?i)(?s)^.*<html[^>]*>", "<html>")
		    .replaceFirst("(?i)(?s)<body [^>]*>", "<body>").replaceAll("(?i)(?s)<script.*?>.*?</script>", "")
		    .replaceAll("(?i)(?s)</?tbody.*?>", "").replaceAll("(?i)(?s)<!--.*?-->", "").replaceAll(
		        "(?i)(?s)</?o[^>]*>", "");
		if (StringUtils.equals(ResourceController.getResourceController().getProperty(
		    "cut_out_pictures_when_pasting_html"), "true")) {
			in = in.replaceAll("(?i)(?s)<img[^>]*>", "");
		}
		in = HtmlUtils.unescapeHTMLUnicodeEntity(in);
		return in;
	}

	/**
	 * @param modeController
	 */
	private void createActions() {
		final ModeController modeController = Controller.getCurrentModeController();
		modeController.addAction(new CutAction());
		modeController.addAction(new PasteAction());
		modeController.addAction(new SelectedPasteAction());
	}

	Transferable cut(final List<NodeModel> collection) {
		Controller.getCurrentModeController().getMapController().sortNodesByDepth(collection);
		final Transferable totalCopy = ((ClipboardController) Controller.getCurrentModeController().getExtension(
		    ClipboardController.class)).copy(collection, true);
		for (final NodeModel node : collection) {
			if (node.getParentNode() != null) {
				((MMapController) Controller.getCurrentModeController().getMapController()).deleteNode(node);
			}
		}
		setClipboardContents(totalCopy);
		return totalCopy;
	}

	private IDataFlavorHandler getFlavorHandler(final Transferable t) {
		if (t.isDataFlavorSupported(MindMapNodesSelection.mindMapNodesFlavor)) {
			try {
				final String textFromClipboard = t.getTransferData(MindMapNodesSelection.mindMapNodesFlavor).toString();
				return new MindMapNodesFlavorHandler(textFromClipboard);
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		if (t.isDataFlavorSupported(MindMapNodesSelection.fileListFlavor)) {
			try {
				final List<File> fileList = castToFileList(t.getTransferData(MindMapNodesSelection.fileListFlavor));
				return new FileListFlavorHandler(fileList);
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		final ResourceController resourceController = ResourceController.getResourceController();
		if (t.isDataFlavorSupported(MindMapNodesSelection.htmlFlavor)) {
			try {
				final String textFromClipboard = t.getTransferData(MindMapNodesSelection.htmlFlavor).toString();
				if (textFromClipboard.charAt(0) != 65533) {
					if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
						final MTextController textController = (MTextController) TextController
						    .getController();
						final boolean richText = textController.useRichTextInEditor(RESOURCES_REMIND_USE_RICH_TEXT_IN_NEW_NODES);
						if (richText) {
							final boolean structuredHtmlImport = resourceController
							    .getBooleanProperty("structured_html_import");
							final IDataFlavorHandler htmlFlavorHandler;
							if (structuredHtmlImport) {
								htmlFlavorHandler = new StructuredHtmlFlavorHandler(textFromClipboard);
							}
							else {
								htmlFlavorHandler = new DirectHtmlFlavorHandler(textFromClipboard);
							}
							return htmlFlavorHandler;
						}
					}
				}
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
			try {
				final String plainTextFromClipboard = t.getTransferData(DataFlavor.stringFlavor).toString();
				return new StringFlavorHandler(plainTextFromClipboard);
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
			try {
				BufferedImage image = (BufferedImage) t.getTransferData(DataFlavor.imageFlavor);
				return new ImageFlavorHandler(image);
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
    private List<File> castToFileList(Object transferData) {
	    return (List<File>) transferData;
    }

	Collection<IDataFlavorHandler> getFlavorHandlers() {
		final Transferable t = getClipboardContents();
		final Collection<IDataFlavorHandler> handlerList = new LinkedList<IDataFlavorHandler>();
		if (t.isDataFlavorSupported(MindMapNodesSelection.mindMapNodesFlavor)) {
			try {
				final String textFromClipboard = t.getTransferData(MindMapNodesSelection.mindMapNodesFlavor).toString();
				handlerList.add(new MindMapNodesFlavorHandler(textFromClipboard));
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		if (t.isDataFlavorSupported(MindMapNodesSelection.htmlFlavor)) {
			try {
				final String textFromClipboard = t.getTransferData(MindMapNodesSelection.htmlFlavor).toString();
				if (textFromClipboard.charAt(0) != 65533) {
					if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
						handlerList.add(new StructuredHtmlFlavorHandler(textFromClipboard));
						handlerList.add(new DirectHtmlFlavorHandler(textFromClipboard));
					}
				}
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
			try {
				final String plainTextFromClipboard = t.getTransferData(DataFlavor.stringFlavor).toString();
				handlerList.add(new StringFlavorHandler(plainTextFromClipboard));
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		if (t.isDataFlavorSupported(MindMapNodesSelection.fileListFlavor)) {
			try {
				final List<File> fileList = castToFileList(t.getTransferData(MindMapNodesSelection.fileListFlavor));
				handlerList.add(new FileListFlavorHandler(fileList));
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
			try {
				BufferedImage image = (BufferedImage) t.getTransferData(DataFlavor.imageFlavor);
				handlerList.add(new ImageFlavorHandler(image));
			}
			catch (final UnsupportedFlavorException e) {
			}
			catch (final IOException e) {
			}
		}
		return handlerList;
	}

	/**
	 * @param t
	 *            the content
	 * @param target
	 *            where to add the content
	 * @param asSibling
	 *            if true, the content is added beside the target, otherwise as
	 *            new children
	 * @param isLeft
	 *            if something is pasted as a sibling to root, it must be
	 *            decided on which side of root
	 * @return true, if successfully executed.
	 */
	public void paste(final Transferable t, final NodeModel target, final boolean asSibling, final boolean isLeft) {
		if (t == null) {
			return;
		}
		/*
		 * DataFlavor[] fl = t.getTransferDataFlavors(); for (int i = 0; i <
		 * fl.length; i++) { System.out.println(fl[i]); }
		 */
		final IDataFlavorHandler handler = getFlavorHandler(t);
		paste(handler, target, asSibling, isLeft);
	}

	void paste(final IDataFlavorHandler handler, final NodeModel target, final boolean asSibling, final boolean isLeft) {
		if (handler == null) {
			return;
		}
		final MMapController mapController = (MMapController) Controller.getCurrentModeController().getMapController();
		if (asSibling && !mapController.isWriteable(target.getParentNode()) || !asSibling
		        && !mapController.isWriteable(target)) {
			final String message = TextUtils.getText("node_is_write_protected");
			UITools.errorMessage(message);
			return;
		}
		try {
			Controller.getCurrentController().getViewController().setWaitingCursor(true);
			if (newNodes == null) {
				newNodes = new LinkedList<NodeModel>();
			}
			newNodes.clear();
			handler.paste(target, asSibling, isLeft);
			final ModeController modeController = Controller.getCurrentModeController();
			if (!asSibling && modeController.getMapController().isFolded(target)
			        && ResourceController.getResourceController().getBooleanProperty(RESOURCE_UNFOLD_ON_PASTE)) {
				modeController.getMapController().setFolded(target, false);
			}
			for (final NodeModel child : newNodes) {
				AttributeController.getController().performRegistrySubtreeAttributes(child);
			}
		}
		finally {
			Controller.getCurrentController().getViewController().setWaitingCursor(false);
		}
	}

	private void pasteStringWithoutRedisplay(final TextFragment[] textFragments, NodeModel parent,
	                                              final boolean asSibling, final boolean isLeft) {
		final MapModel map = parent.getMap();
		int insertionIndex;
		if (asSibling) {
			NodeModel target = parent;
			parent = parent.getParentNode();
			insertionIndex = parent.getChildPosition(target);
		}
		else{
			insertionIndex = parent.getChildCount();
		}
		final ArrayList<NodeModel> parentNodes = new ArrayList<NodeModel>();
		final ArrayList<Integer> parentNodesDepths = new ArrayList<Integer>();
		parentNodes.add(parent);
		parentNodesDepths.add(new Integer(-1));
		final MMapController mapController = (MMapController) Controller.getCurrentModeController().getMapController();	
		for (int i = 0; i < textFragments.length; ++i) {
			final TextFragment textFragment = textFragments[i];
			final String text = textFragment.text;
			final NodeModel node = mapController.newNode(text, map);
			if (textFragment.link != null) {
				((MLinkController) LinkController.getController()).setLinkTypeDependantLink(node, textFragment.link);
			}
			for (int j = parentNodes.size() - 1; j >= 0; --j) {
				if (textFragment.depth > ((Integer) parentNodesDepths.get(j)).intValue()) {
					for (int k = j + 1; k < parentNodes.size(); ++k) {
						final NodeModel n = (NodeModel) parentNodes.get(k);
						if (n.getParentNode() == null) {
							mapController.insertNode(n, parent, insertionIndex++);
						}
						parentNodes.remove(k);
						parentNodesDepths.remove(k);
					}
					final NodeModel target = (NodeModel) parentNodes.get(j);
					node.setLeft(isLeft);
					if (target != parent) {
						target.setFolded(true);
						target.insert(node, target.getChildCount());
					}
					parentNodes.add(node);
					parentNodesDepths.add(new Integer(textFragment.depth));
					break;
				}
			}
		}
		{
			for (int k = 0; k < parentNodes.size(); ++k) {
				final NodeModel n = (NodeModel) parentNodes.get(k);
				if (map.getRootNode() != n && n.getParentNode() == null) {
					mapController.insertNode(n, parent, insertionIndex++);
				}
			}
		}
	}
}
