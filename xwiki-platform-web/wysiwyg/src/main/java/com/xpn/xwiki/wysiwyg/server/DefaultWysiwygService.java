/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xpn.xwiki.wysiwyg.server;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.velocity.VelocityContext;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.officeimporter.OfficeImporter;
import org.xwiki.officeimporter.OfficeImporterException;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.macro.Macro;
import org.xwiki.rendering.macro.MacroFactory;
import org.xwiki.rendering.parser.ParseException;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.rendering.parser.Syntax;
import org.xwiki.rendering.parser.SyntaxFactory;
import org.xwiki.rendering.parser.SyntaxType;
import org.xwiki.rendering.renderer.PrintRendererFactory;
import org.xwiki.rendering.renderer.XHTMLRenderer;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.renderer.printer.WikiPrinter;
import org.xwiki.rendering.transformation.TransformationException;
import org.xwiki.rendering.transformation.TransformationManager;
import org.xwiki.xml.XMLUtils;
import org.xwiki.xml.html.HTMLCleanerConfiguration;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Context;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.api.XWiki;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.gwt.api.client.XWikiGWTException;
import com.xpn.xwiki.gwt.api.server.XWikiServiceImpl;
import com.xpn.xwiki.web.Utils;
import com.xpn.xwiki.wysiwyg.client.WysiwygService;
import com.xpn.xwiki.wysiwyg.client.diff.Revision;
import com.xpn.xwiki.wysiwyg.client.plugin.image.ImageConfig;
import com.xpn.xwiki.wysiwyg.client.plugin.link.LinkConfig;
import com.xpn.xwiki.wysiwyg.client.plugin.macro.MacroDescriptor;
import com.xpn.xwiki.wysiwyg.client.plugin.macro.ParameterDescriptor;
import com.xpn.xwiki.wysiwyg.client.sync.SyncResult;
import com.xpn.xwiki.wysiwyg.client.sync.SyncStatus;
import com.xpn.xwiki.wysiwyg.server.cleaner.HTMLCleaner;
import com.xpn.xwiki.wysiwyg.server.converter.HTMLConverter;
import com.xpn.xwiki.wysiwyg.server.sync.DefaultSyncEngine;
import com.xpn.xwiki.wysiwyg.server.sync.SyncEngine;

/**
 * The default implementation for {@link WysiwygService}.
 * 
 * @version $Id$
 */
public class DefaultWysiwygService extends XWikiServiceImpl implements WysiwygService
{
    /**
     * Class version.
     */
    private static final long serialVersionUID = 7555724420345951844L;

    /**
     * Default XWiki logger to report errors correctly.
     */
    private static final Log LOG = LogFactory.getLog(DefaultWysiwygService.class);

    /**
     * The object used to synchronize the content edited by multiple users when the real time feature of the editor is
     * activated.
     */
    private SyncEngine syncEngine;

    /**
     * Default constructor.
     */
    public DefaultWysiwygService()
    {
        syncEngine = new DefaultSyncEngine();
    }

    /**
     * Return the component that is used to clean the HTML generated by the WYSIWYG editor. This component is needed to
     * filter the HTML elements that are added by the editor for internal reasons.
     * 
     * @return The component used for cleaning the HTML generated by the editor.
     */
    private HTMLCleaner getHTMLCleaner()
    {
        return (HTMLCleaner) Utils.getComponent(HTMLCleaner.ROLE);
    }

    /**
     * @param syntax The syntax for which we retrieve the HTML converter.
     * @return The component used for converting the HTML generated by the editor into/from the specified syntax.
     */
    private HTMLConverter getHTMLConverter(String syntax)
    {
        return (HTMLConverter) Utils.getComponent(HTMLConverter.ROLE, syntax);
    }

    /**
     * @return The component used to access documents. This is temporary till XWiki model is moved into components.
     */
    private DocumentAccessBridge getDocumentAccessBridge()
    {
        return (DocumentAccessBridge) Utils.getComponent(DocumentAccessBridge.ROLE);
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#fromHTML(String, String)
     */
    public String fromHTML(String html, String syntax)
    {
        return getHTMLConverter(syntax).fromHTML(cleanHTML(html));
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#toHTML(String, String)
     */
    public String toHTML(String source, String syntax)
    {
        return getHTMLConverter(syntax).toHTML(source);
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#cleanHTML(String)
     */
    public String cleanHTML(String dirtyHTML)
    {
        return getHTMLCleaner().clean(dirtyHTML);
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#parseAndRender(String, String)
     */
    public String parseAndRender(String html, String syntax)
    {
        try {
            Syntax xhtmlSyntax = new Syntax(SyntaxType.XHTML, "1.0");

            // Parse
            Parser parser = (Parser) Utils.getComponent(Parser.ROLE, xhtmlSyntax.toIdString());
            XDOM dom = parser.parse(new StringReader(cleanHTML(html)));

            // Execute macros
            SyntaxFactory syntaxFactory = (SyntaxFactory) Utils.getComponent(SyntaxFactory.ROLE);
            TransformationManager txManager = (TransformationManager) Utils.getComponent(TransformationManager.ROLE);
            txManager.performTransformations(dom, syntaxFactory.createSyntaxFromIdString(syntax));

            // Render
            WikiPrinter printer = new DefaultWikiPrinter();
            PrintRendererFactory factory = (PrintRendererFactory) Utils.getComponent(PrintRendererFactory.ROLE);
            XHTMLRenderer renderer = (XHTMLRenderer) factory.createRenderer(xhtmlSyntax, printer);
            dom.traverse(renderer);

            return printer.toString();
        } catch (ParseException e) {
            throw new RuntimeException("Exception while parsing HTML", e);
        } catch (TransformationException e) {
            throw new RuntimeException("Exception while executing macros", e);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#cleanOfficeHTML(String, String)
     */
    public String cleanOfficeHTML(String htmlPaste, String cleanerHint, Map<String, String> cleaningParams)
    {
        org.xwiki.xml.html.HTMLCleaner cleaner =
            (org.xwiki.xml.html.HTMLCleaner) Utils.getComponent(org.xwiki.xml.html.HTMLCleaner.ROLE, cleanerHint);
        HTMLCleanerConfiguration configuration = cleaner.getDefaultConfiguration();
        configuration.setParameters(cleaningParams);
        return XMLUtils.toString(cleaner.clean(new StringReader(htmlPaste), configuration));
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#officeToXHTML(String, Map)
     */
    public String officeToXHTML(String pageName, Map<String, String> cleaningParams) throws XWikiGWTException
    {
        OfficeImporter officeImporter = (OfficeImporter) Utils.getComponent(OfficeImporter.ROLE);
        XWikiContext context = getXWikiContext();
        try {
            List<XWikiAttachment> attachments = context.getWiki().getDocument(pageName, context).getAttachmentList();
            XWikiAttachment latestAttachment = Collections.max(attachments, new Comparator<XWikiAttachment>()
            {
                public int compare(XWikiAttachment firstAttachment, XWikiAttachment secondAttachment)
                {
                    String currentAuthor = "";
                    try {
                        currentAuthor = getUser().getAuthor();
                    } catch (XWikiGWTException e) {
                        // Do nothing.
                    }
                    if (firstAttachment.getAuthor().equals(currentAuthor)
                        && secondAttachment.getAuthor().equals(currentAuthor)) {
                        return firstAttachment.getDate().compareTo(secondAttachment.getDate());
                    } else if(firstAttachment.getAuthor().equals(currentAuthor)) {
                        return +1;
                    } else if(secondAttachment.getAuthor().equals(currentAuthor)){
                        return -1;
                    } else {
                        return 0;
                    }
                }
            });
            return officeImporter.importAttachment(pageName, latestAttachment.getFilename(), cleaningParams);
        } catch (OfficeImporterException ex) {
            throw new XWikiGWTException(ex.getMessage(), ex.getMessage(), -1, -1);
        } catch (XWikiException ex) {
            throw new XWikiGWTException(ex.getMessage(), ex.getMessage(), -1, -1);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#syncEditorContent(Revision, String, int)
     */
    public synchronized SyncResult syncEditorContent(Revision revision, String pageName, int version, boolean syncReset)
        throws XWikiGWTException
    {
        try {
            XWikiContext context = getXWikiContext();
            SyncStatus syncStatus = syncEngine.getSyncStatus(pageName);
            XWikiDocument doc = context.getWiki().getDocument(pageName, context);
            String docVersion = doc.getVersion();
            if ((syncStatus == null) || syncReset) {
                VelocityContext vcontext = (VelocityContext) context.get("vcontext");
                if (vcontext == null) {
                    vcontext = new VelocityContext();
                    vcontext.put("context", new Context(context));
                    vcontext.put("request", context.getRequest());
                    vcontext.put("response", context.getResponse());
                    vcontext.put("util", context.getUtil());
                    vcontext.put("xwiki", new XWiki(context.getWiki(), context));
                    context.put("vcontext", vcontext);
                }
                Document doc2 = doc.newDocument(context);
                vcontext.put("tdoc", doc2);
                vcontext.put("doc", doc2);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Initial content wiki syntax: " + doc.getContent());
                }
                String html = context.getWiki().parseTemplate("wysiwyginput.vm", context);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Initial content html: " + html);
                }
                syncStatus = new SyncStatus(pageName, docVersion, html);
                syncEngine.setSyncStatus(pageName, syncStatus);
            } else {
                // we need to check the version versus the one that was initially loaded
                // if the version is different then we should handle this

            }
            return syncEngine.sync(syncStatus, revision, version);
        } catch (Exception e) {
            throw getXWikiGWTException(e);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#isMultiWiki()
     */
    public Boolean isMultiWiki()
    {
        return getXWikiContext().getWiki().isVirtualMode();
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#getVirtualWikiNames()
     */
    public List<String> getVirtualWikiNames()
    {
        List<String> virtualWikiNamesList = new ArrayList<String>();
        try {
            virtualWikiNamesList = getXWikiContext().getWiki().getVirtualWikisDatabaseNames(getXWikiContext());
            // put the current, default database if nothing is inside
            if (virtualWikiNamesList.size() == 0) {
                virtualWikiNamesList.add(getXWikiContext().getDatabase());
            }
            Collections.sort(virtualWikiNamesList);
        } catch (XWikiException e) {
            e.printStackTrace();
        }
        return virtualWikiNamesList;
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#getSpaceNames(String)
     */
    public List<String> getSpaceNames(String wikiName)
    {
        List<String> spaceNamesList = new ArrayList<String>();
        String database = getXWikiContext().getDatabase();
        try {
            if (wikiName != null) {
                getXWikiContext().setDatabase(wikiName);
            }
            spaceNamesList = getXWikiContext().getWiki().getSpaces(getXWikiContext());
            Collections.sort(spaceNamesList);
        } catch (XWikiException e) {
            e.printStackTrace();
        } finally {
            if (wikiName != null) {
                getXWikiContext().setDatabase(database);
            }
        }
        return spaceNamesList;
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#getPageNames(String, String)
     */
    public List<String> getPageNames(String wikiName, String spaceName)
    {
        String database = getXWikiContext().getDatabase();
        List<String> pagesFullNameList = null;
        List<String> pagesNameList = new ArrayList<String>();
        List<String> params = new ArrayList<String>();
        params.add(spaceName);
        String query = "where doc.space = ? order by doc.fullName asc";
        try {
            if (wikiName != null) {
                getXWikiContext().setDatabase(wikiName);
            }
            pagesFullNameList =
                getXWikiContext().getWiki().getStore().searchDocumentsNames(query, params, getXWikiContext());
        } catch (XWikiException e) {
            e.printStackTrace();
        } finally {
            if (wikiName != null) {
                getXWikiContext().setDatabase(database);
            }
        }
        if (pagesFullNameList != null) {
            for (String p : pagesFullNameList) {
                pagesNameList.add(p.substring(params.get(0).length() + 1));
            }
        }
        return pagesNameList;
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#getPageLink(String, String, String, String, String)
     */
    public LinkConfig getPageLink(String wikiName, String spaceName, String pageName, String revision, String anchor)
    {
        XWikiContext context = getXWikiContext();
        String database = context.getDatabase();
        String newPageName = pageName;
        String newSpaceName = spaceName;
        String pageURL = null;
        LinkConfig linkConfig = new LinkConfig();
        try {
            if (wikiName != null) {
                context.setDatabase(wikiName);
            }
            // if we have no page name, link to the WebHome of whatever space
            if (newPageName == null || newPageName.length() == 0) {
                newPageName = "WebHome";
            }
            // if we have no space, link to the current doc's space
            if (newSpaceName == null && newSpaceName.length() == 0) {
                if ((newPageName == null || newPageName.length() == 0) && wikiName != null && wikiName.length() > 0) {
                    // if we have no space set and no page but we have a wiki, then create a link to the mainpage of the
                    // wiki
                    newSpaceName = "Main";
                } else {
                    newSpaceName = context.getDoc().getSpace();
                }
            }

            // clear the page and space name, to make sure we link to the right page
            newPageName = clearXWikiName(newPageName);
            newSpaceName = clearXWikiName(newSpaceName);

            XWikiDocument requestedDocument = context.getWiki().getDocument(newSpaceName + "." + newPageName, context);
            pageURL = requestedDocument.getURL("view", context);
            linkConfig.setUrl(pageURL);
            linkConfig.setPage(requestedDocument.getName());
            linkConfig.setSpace(requestedDocument.getSpace());
            linkConfig.setWiki(wikiName);
            // if we have revision, get document with revision, otherwise get simple document
            if (revision != null && revision.length() > 0) {
                requestedDocument = context.getWiki().getDocument(newSpaceName + "." + newPageName, context);
                pageURL = requestedDocument.getURL("viewrev", "rev=" + revision, context);
            }
            if (anchor != null && anchor.length() > 0) {
                pageURL += "#" + anchor;
            }
        } catch (XWikiException e) {
            e.printStackTrace();
        } finally {
            if (wikiName != null) {
                getXWikiContext().setDatabase(database);
            }
        }

        return linkConfig;
    }

    /**
     * Clears forbidden characters out of the passed name, in a way which is consistent with the algorithm used in the
     * create page panel. <br /> FIXME: this function needs to be deleted when there will be a function to do this
     * operation in a consistent manner across the whole xwiki, and all calls to this function should be replaced with
     * calls to that function.
     * 
     * @param name the name to clear from forbidden characters and transform in a xwiki name.
     * @return the cleared up xwiki name, ready to be used as a page or space name.
     */
    private String clearXWikiName(String name)
    {
        // replace all / with .
        return name.replace('/', '.');
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#getImageAttachments(String, String, String)
     */
    public List<ImageConfig> getImageAttachments(String wikiName, String spaceName, String pageName)
    {
        XWikiContext context = getXWikiContext();
        String database = context.getDatabase();
        List<ImageConfig> imageAttachs = new ArrayList<ImageConfig>();
        try {
            if (wikiName != null) {
                context.setDatabase(wikiName);
            }
            // get the document referred by the parameters
            // TODO: handle missing space name or missing page name
            XWikiDocument doc = context.getWiki().getDocument(spaceName + "." + pageName, context);
            // TODO: handle attachment sort
            for (XWikiAttachment attach : doc.getAttachmentList()) {
                if (attach.getMimeType(getXWikiContext()).startsWith("image/")) {
                    ImageConfig img = new ImageConfig();
                    img.setImageFileName(attach.getFilename());
                    img.setImageURL(doc.getAttachmentURL(attach.getFilename(), context));
                    img.setWiki(wikiName);
                    img.setPage(doc.getName());
                    img.setSpace(doc.getSpace());
                    imageAttachs.add(img);
                }
            }
            return imageAttachs;
        } catch (XWikiException e) {
            LOG.error("Error while getting the image attachments list", e);
        } finally {
            if (wikiName != null) {
                getXWikiContext().setDatabase(database);
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#getMacroDescriptor(String, String)
     */
    public MacroDescriptor getMacroDescriptor(String macroName, String syntaxId) throws XWikiGWTException
    {
        try {
            SyntaxFactory syntaxFactory = (SyntaxFactory) Utils.getComponent(SyntaxFactory.ROLE);
            MacroFactory macroFactory = (MacroFactory) Utils.getComponent(MacroFactory.ROLE);
            Macro< ? > macro = macroFactory.getMacro(macroName, syntaxFactory.createSyntaxFromIdString(syntaxId));
            org.xwiki.rendering.macro.descriptor.MacroDescriptor descriptor = macro.getDescriptor();

            MacroDescriptor result = new MacroDescriptor();
            result.setDescription(descriptor.getDescription());
            Map<String, ParameterDescriptor> parameterDescriptorMap = new HashMap<String, ParameterDescriptor>();
            for (Map.Entry<String, org.xwiki.rendering.macro.descriptor.ParameterDescriptor> entry : descriptor
                .getParameterDescriptorMap().entrySet()) {
                ParameterDescriptor parameterDescriptor = new ParameterDescriptor();
                parameterDescriptor.setName(entry.getValue().getName());
                parameterDescriptor.setDescription(entry.getValue().getDescription());
                parameterDescriptor.setType(entry.getValue().getType().getName());
                parameterDescriptor.setMandatory(entry.getValue().isMandatory());
                parameterDescriptorMap.put(entry.getKey(), parameterDescriptor);
            }
            result.setParameterDescriptorMap(parameterDescriptorMap);
            return result;
        } catch (Throwable t) {
            LOG.error("Exception while retrieving macro descriptor.", t);
            throw new XWikiGWTException(t.getLocalizedMessage(), t.toString(), -1, -1);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see WysiwygService#getMacros(String)
     */
    public List<String> getMacros(String syntaxId) throws XWikiGWTException
    {
        try {
            List<String> macros = new ArrayList<String>();
            for (Object key : Utils.getComponentManager().lookupMap(Macro.ROLE).keySet()) {
                macros.add(key.toString());
            }
            Collections.sort(macros);
            return macros;
        } catch (Throwable t) {
            LOG.error("Exception while retrieving the list of available macros.", t);
            throw new XWikiGWTException(t.getLocalizedMessage(), t.toString(), -1, -1);
        }
    }
}
