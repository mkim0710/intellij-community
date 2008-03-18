package com.intellij.xml.util;

import com.intellij.javaee.ExternalResourceManagerEx;
import com.intellij.javaee.ExternalResourceManager;
import com.intellij.javaee.UriUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.xml.actions.ValidateXmlActionHandler;
import com.intellij.xml.XmlBundle;
import org.apache.xerces.xni.XMLResourceIdentifier;
import org.apache.xerces.xni.XNIException;
import org.apache.xerces.xni.parser.XMLEntityResolver;
import org.apache.xerces.xni.parser.XMLInputSource;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: Maxim.Mossienko
 * Date: Aug 6, 2004
 * Time: 6:48:55 PM
 * To change this template use File | Settings | File Templates.
 */
public class XmlResourceResolver implements XMLEntityResolver {
  private static final Logger LOG = Logger.getInstance("#com.intellij.xml.util.XmlResourceResolver");
  private XmlFile myFile;
  private Project myProject;
  private Map<String,String> myExternalResourcesMap = new HashMap<String, String>(1);
  private boolean myStopOnUnDeclaredResource;
  @NonNls
  public static final String FILE_PREFIX = "file://";
  private ValidateXmlActionHandler.ErrorReporter myErrorReporter;

  public XmlResourceResolver(XmlFile _xmlFile, Project _project, final ValidateXmlActionHandler.ErrorReporter errorReporter) {
    myFile = _xmlFile;
    myProject = _project;
    myErrorReporter = errorReporter;
  }

  public String getPathByPublicId(String baseId) {
    return myExternalResourcesMap.get(baseId);
  }

  public String[] getResourcePaths() {
    return myExternalResourcesMap.values().toArray(new String[myExternalResourcesMap.size()]);
  }

  @Nullable
  public PsiFile resolve(final String baseSystemId, final String systemId) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: resolveEntity(baseSystemId='" + baseSystemId + "' systemId='" + systemId + "," + super.toString() + "')");
    }

    if (systemId == null) return null;
    if (myStopOnUnDeclaredResource &&
        ExternalResourceManagerEx.getInstanceEx().isIgnoredResource(systemId)) {
      throw new IgnoredResourceException();
    }
    final PsiFile[] result = new PsiFile[] { null };
    final Runnable action = new Runnable() {
      public void run() {
        PsiFile baseFile = null;
        VirtualFile vFile = null;

        if (baseSystemId != null) {
          baseFile = resolve(null,baseSystemId);

          if (baseFile == null) {
            if (myFile != null) {
              // Find relative to myFile
              File workingFile = new File("");
              String workingDir = workingFile.getAbsoluteFile().getAbsolutePath().replace(File.separatorChar, '/');
              String id = StringUtil.replace(baseSystemId, workingDir, myFile.getVirtualFile().getParent().getPath());
              vFile = UriUtil.findRelativeFile(id, null);
            }

            if (vFile == null) {
              vFile = UriUtil.findRelativeFile(baseSystemId, null);

              if (vFile == null) {
                try {
                  vFile = VfsUtil.findFileByURL(new URL(baseSystemId));
                } catch(MalformedURLException ex) {}
              }
            }
          }

          if (vFile != null && !vFile.isDirectory()) {
            baseFile = PsiManager.getInstance(myProject).findFile(vFile);
          }
        }
        if (baseFile == null) {
          baseFile = myFile;
        }
        PsiFile psiFile = ExternalResourceManager.getInstance().getResourceLocation(systemId, baseFile, null);
        if (psiFile == null) {
          psiFile = XmlUtil.findXmlFile(baseFile, systemId);
        }

        if (psiFile == null && baseSystemId != null) {
          String fullUrl = baseSystemId.substring( 0, baseSystemId.lastIndexOf('/') + 1 ) + systemId;
          psiFile = XmlUtil.findXmlFile(baseFile,fullUrl);
        }

        if (LOG.isDebugEnabled()) {
          LOG.debug("before relative file checking:"+psiFile+","+systemId+","+ baseSystemId+")");
        }
        if (psiFile == null && baseSystemId == null) { // entity file
          File workingFile = new File("");
          String workingDir = workingFile.getAbsoluteFile().getAbsolutePath().replace(File.separatorChar, '/') + "/";

          String relativePath = StringUtil.replace(
            systemId,
            workingDir,
            ""
          );

          if (relativePath.equals(systemId)) {
            // on Windows systemId consisting of idea install path could become encoded DOS short name (e.g. idea%7f1.504)
            // I am not aware how to get such name from 'workingDir' so let just pickup filename from there
            relativePath = systemId.substring(systemId.lastIndexOf('/') + 1);
          }

          if (LOG.isDebugEnabled()) {
            LOG.debug("next to relative file checking:"+relativePath+","+myExternalResourcesMap.size()+")");
          }

          for(String path:myExternalResourcesMap.values()) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Finding file by url:" + path);
            }
            VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(path);
            if (file == null) continue;
            if (LOG.isDebugEnabled()) {
              LOG.debug("Finding "+relativePath+" relative to:"+file.getPath());
            }
            final VirtualFile relativeFile = UriUtil.findRelativeFile(relativePath, file);
            if (LOG.isDebugEnabled()) {
              LOG.debug("Found "+(relativeFile != null ? relativeFile.getPath():"null"));
            }

            if (relativeFile != null) {
              psiFile = PsiManager.getInstance(myProject).findFile(relativeFile);
              if (psiFile != null) break;
            }
          }
        }

        if (LOG.isDebugEnabled()) {
          LOG.debug("resolveEntity: psiFile='" + (psiFile != null ? psiFile.getVirtualFile() : null) + "'");
        }
        result[0] = psiFile;
      }
    };
    ApplicationManager.getApplication().runReadAction(action);

    final PsiFile psiFile = result[0];
    if (psiFile != null) {
      final String url = psiFile.getVirtualFile().getUrl();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Adding external resource ref:"+systemId+","+url+","+super.toString());
      }
      myExternalResourcesMap.put(systemId,url);
    }
    return psiFile;
  }

  @Nullable
  public XMLInputSource resolveEntity(XMLResourceIdentifier xmlResourceIdentifier) throws XNIException, IOException {
    String publicId  = xmlResourceIdentifier.getLiteralSystemId() != null ?
                  xmlResourceIdentifier.getLiteralSystemId():
                  xmlResourceIdentifier.getNamespace();

    PsiFile psiFile = resolve(xmlResourceIdentifier.getBaseSystemId(), publicId);
    if (psiFile == null && xmlResourceIdentifier.getBaseSystemId() != null) {
        psiFile = ExternalResourceManager.getInstance().getResourceLocation(xmlResourceIdentifier.getBaseSystemId(), myFile, null);
    }
    if (psiFile==null && xmlResourceIdentifier.getLiteralSystemId()!=null && xmlResourceIdentifier.getNamespace()!=null) {
      psiFile = resolve(
        xmlResourceIdentifier.getBaseSystemId(),
        publicId = xmlResourceIdentifier.getNamespace()
      );
    }

    if (psiFile == null) {
      if (publicId != null && publicId.indexOf(":/") != -1) {
        myErrorReporter.processError(
          new SAXParseException(XmlBundle.message("xml.validate.external.resource.is.not.registered", publicId), publicId, null, 0,0), false);
        final XMLInputSource source = new XMLInputSource(xmlResourceIdentifier);
        source.setPublicId(publicId);
        source.setCharacterStream(new StringReader(""));
        return source;
      }
      return null;
    }

    XMLInputSource source = new XMLInputSource(xmlResourceIdentifier);
    if (xmlResourceIdentifier.getLiteralSystemId() == null) {
      VirtualFile virtualFile = psiFile.getVirtualFile();
      final String url = VfsUtil.fixIDEAUrl(virtualFile.getUrl());
      source.setBaseSystemId(url);
      source.setSystemId(url);
    }
    source.setPublicId(publicId);
    source.setCharacterStream(new StringReader(psiFile.getText()));

    return source;
  }

  public void setStopOnUnDeclaredResource(final boolean stopOnUnDeclaredResource) {
    myStopOnUnDeclaredResource = stopOnUnDeclaredResource;
  }

  public static class IgnoredResourceException extends RuntimeException {
  }
}
