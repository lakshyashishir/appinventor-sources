// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2012 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.server;

import com.google.appinventor.server.util.CacheHeaders;
import com.google.appinventor.server.util.CacheHeadersImpl;
import com.google.appinventor.shared.rpc.ServerLayout;
import com.google.appinventor.shared.rpc.UploadResponse;
import com.google.appinventor.shared.rpc.component.Component;
import com.google.appinventor.shared.rpc.project.UserProject;

import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet for uploading files.
 *
 */
public class UploadServlet extends OdeServlet {

  /*
   * URIs for upload requests are structured as follows:
   *    /<baseurl>/upload/project/<projectname>}
   *    /<baseurl>/upload/file/<projectId>/<filePath>
   *    /<baseurl>/upload/userfile/<filePath>
   */

  // Constants for accessing split URI
  /*
   * Upload kind can be: "project", "file", or "userfile".
   * Constants for these are defined in ServerLayout.
   */
  private static final int UPLOAD_KIND_INDEX = 3;

  // Constants used when upload kind is "project".
  private static final int PROJECT_TITLE_INDEX = 4;
  private static final int SPLIT_LIMIT_PROJECT_SOURCE = 5;

  // Constants used when upload kind is "file".
  // Since the file path may contain slashes, it must be the last component in the URI.
  private static final int PROJECT_ID_INDEX = 4;
  private static final int FILE_PATH_INDEX = 5;
  private static final int SPLIT_LIMIT_FILE = 6;

  // Constants used when upload kind is "userfile".
  // Since the file path may contain slashes, it must be the last component in the URI.
  private static final int USERFILE_PATH_INDEX = 4;
  private static final int SPLIT_LIMIT_USERFILE = 5;

  // Constants used when upload kind is "component".
  // Since the file path may contain slashes, it must be the last component in the URI.
  private static final int COMPONENT_PATH_INDEX = 4;
  private static final int SPLIT_LIMIT_COMPONENT = 5;


  // Logging support
  private static final Logger LOG = Logger.getLogger(UploadServlet.class.getName());

  // Object used to safely set cache headers in responses
  private static final CacheHeaders CACHE_HEADERS = new CacheHeadersImpl();

  // Content type for response header (to avoid security vulnerabilities)
  private static final String CONTENT_TYPE = "text/html; charset=utf-8";

  private final FileImporter fileImporter = new FileImporterImpl();

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) {
    setDefaultHeader(resp);

    UploadResponse uploadResponse;

    try {
      String uri = req.getRequestURI();
      // First, call split with no limit parameter.
      String[] uriComponents = uri.split("/");
      String uploadKind = uriComponents[UPLOAD_KIND_INDEX];

      if (uploadKind.equals(ServerLayout.UPLOAD_PROJECT)) {
        uriComponents = uri.split("/", SPLIT_LIMIT_PROJECT_SOURCE);
        String projectName = uriComponents[PROJECT_TITLE_INDEX];
        InputStream uploadedStream;
        try {
          uploadedStream = getRequestStream(req, ServerLayout.UPLOAD_PROJECT_ARCHIVE_FORM_ELEMENT);
        } catch (Exception e) {
          throw CrashReport.createAndLogError(LOG, req, null, e);
        }

        try {
          UserProject userProject = fileImporter.importProject(userInfoProvider.getUserId(),
              projectName, uploadedStream);
          String info = userProject.toString();
          uploadResponse = new UploadResponse(UploadResponse.Status.SUCCESS, 0, info);
        } catch (FileImporterException e) {
          uploadResponse = e.uploadResponse;
        }
      } else if (uploadKind.equals(ServerLayout.UPLOAD_FILE)) {
        uriComponents = uri.split("/", SPLIT_LIMIT_FILE);
        long projectId = Long.parseLong(uriComponents[PROJECT_ID_INDEX]);
        String fileName = uriComponents[FILE_PATH_INDEX];
        InputStream uploadedStream;
        try {
          uploadedStream = getRequestStream(req, ServerLayout.UPLOAD_FILE_FORM_ELEMENT);
        } catch (Exception e) {
          throw CrashReport.createAndLogError(LOG, req, null, e);
        }

        try {
          long modificationDate = fileImporter.importFile(userInfoProvider.getUserId(),
              projectId, fileName, uploadedStream);
          uploadResponse = new UploadResponse(UploadResponse.Status.SUCCESS, modificationDate);
        } catch (FileImporterException e) {
          uploadResponse = e.uploadResponse;
        }
      } else if (uploadKind.equals(ServerLayout.UPLOAD_USERFILE)) {
        uriComponents = uri.split("/", SPLIT_LIMIT_USERFILE);
        if (USERFILE_PATH_INDEX >= uriComponents.length) {
          throw CrashReport.createAndLogError(LOG, req, null,
              new IllegalArgumentException("Missing user file path."));
        }
        String fileName = uriComponents[USERFILE_PATH_INDEX];
        InputStream uploadedStream;
        try {
          uploadedStream = getRequestStream(req, ServerLayout.UPLOAD_USERFILE_FORM_ELEMENT);
        } catch (Exception e) {
          throw CrashReport.createAndLogError(LOG, req, null, e);
        }

        fileImporter.importUserFile(userInfoProvider.getUserId(), fileName, uploadedStream);
        uploadResponse = new UploadResponse(UploadResponse.Status.SUCCESS);
      } else if (uploadKind.equals(ServerLayout.UPLOAD_COMPONENT)) {
        uriComponents = uri.split("/", SPLIT_LIMIT_COMPONENT);
        if (COMPONENT_PATH_INDEX >= uriComponents.length) {
          throw CrashReport.createAndLogError(LOG, req, null,
              new IllegalArgumentException("Missing component file path."));
        }

        InputStream uploadedStream;
        try {
          uploadedStream = getRequestStream(req,
              ServerLayout.UPLOAD_COMPONENT_ARCHIVE_FORM_ELEMENT);
        } catch (Exception e) {
          throw CrashReport.createAndLogError(LOG, req, null, e);
        }

        uploadResponse = new UploadResponse(UploadResponse.Status.SUCCESS, 0,
          fileImporter.importTempFile(uploadedStream));
      } else if (uploadKind.equals(ServerLayout.UPLOAD_GLOBAL_ASSET)) {
        String assetName = null;
        String assetType = null;
        String assetFolder = null;
        InputStream uploadedStream = null;
        long assetId = 0;

        try {
          ServletFileUpload upload = new ServletFileUpload();
          FileItemIterator iterator = upload.getItemIterator(req);
          while (iterator.hasNext()) {
            FileItemStream item = iterator.next();
            InputStream stream = item.openStream();
            if (item.isFormField()) {
              String fieldName = item.getFieldName();
              String value = Streams.asString(stream); // Consume the stream
              if ("assetName".equals(fieldName)) {
                assetName = value;
              } else if ("assetType".equals(fieldName)) {
                assetType = value;
              } else if ("assetFolder".equals(fieldName)) {
                assetFolder = value;
              }
            } else {
              if (ServerLayout.UPLOAD_GLOBAL_ASSET_FORM_ELEMENT.equals(item.getFieldName())) {
                // Check if we already have a stream, which would be an error (e.g. multiple files)
                if (uploadedStream != null) {
                  LOG.warning("Multiple files found for field: " + item.getFieldName() + ". Ignoring subsequent ones.");
                  stream.close(); // Close the new stream as we are ignoring it
                } else {
                  uploadedStream = stream; // Don't close this stream here, importer will
                }
              } else {
                LOG.warning("Unexpected file field in global asset upload: " + item.getFieldName());
                stream.close(); // Close unused file streams
              }
            }
          }

          if (assetName == null || assetType == null || uploadedStream == null) {
            LOG.severe("Missing required fields for global asset upload (name, type, or file).");
            uploadResponse = new UploadResponse(UploadResponse.Status.IO_EXCEPTION, 0, "Missing required fields for global asset upload (name, type, or file).");
          } else {
            assetId = fileImporter.importGlobalAsset(userInfoProvider.getUserId(), assetName,
                assetType, assetFolder, uploadedStream);
            uploadResponse = new UploadResponse(UploadResponse.Status.SUCCESS, assetId);
          }
        } catch (FileImporterException e) {
          LOG.warning("FileImporterException during global asset upload: " + e.getMessage());
          uploadResponse = e.uploadResponse;
        } catch (IOException e) {
          LOG.severe("IOException during global asset upload: " + e.getMessage());
          uploadResponse = new UploadResponse(UploadResponse.Status.IO_EXCEPTION, 0, "IO Error during global asset upload: " + e.getMessage());
          throw CrashReport.createAndLogError(LOG, req, "IOException during global asset upload", e);
        } catch (Exception e) { // Catch other general exceptions from fileupload
          LOG.severe("Exception during global asset upload processing: " + e.getMessage());
          uploadResponse = new UploadResponse(UploadResponse.Status.IO_EXCEPTION, 0, "Server error during global asset upload: " + e.getMessage());
          throw CrashReport.createAndLogError(LOG, req, "Exception during global asset upload processing", e);
        } finally {
          // Ensure uploadedStream is closed if it was opened and not passed to importer
          // However, the importer is responsible for closing the stream it receives.
          // If an exception occurred before calling the importer, and uploadedStream was assigned,
          // it might need closing here, but typical stream handling in importer should cover it.
        }

      } else {
        throw CrashReport.createAndLogError(LOG, req, null,
            new IllegalArgumentException("Unknown upload kind: " + uploadKind));
      }

      // Now, get the PrintWriter for the servlet response and print the UploadResponse.
      // On the client side, in the onSubmitComplete method in ode/client/utils/Uploader.java, the
      // UploadResponse value will be retrieved as a String via the
      // FormSubmitCompleteEvent.getResults() method.
      PrintWriter out = resp.getWriter();
      out.print(uploadResponse.formatAsHtml());

    } catch (IOException e) {
      throw CrashReport.createAndLogError(LOG, req, null, e);
    }

    // Set http response information
    resp.setStatus(HttpServletResponse.SC_OK);
  }

  private InputStream getRequestStream(HttpServletRequest req, String expectedFieldName)
      throws Exception {
    ServletFileUpload upload = new ServletFileUpload();
    FileItemIterator iterator = upload.getItemIterator(req);
    while (iterator.hasNext()) {
      FileItemStream item = iterator.next();
      if (item.getFieldName().equals(expectedFieldName)) {
        return item.openStream();
      }
    }

    throw new IllegalArgumentException("Field " + expectedFieldName + " not found in upload");
  }

  /**
   * Set a default http header to avoid security vulnerabilities.
   */
  private static void setDefaultHeader(HttpServletResponse resp) {
    CACHE_HEADERS.setNotCacheable(resp);
    resp.setContentType(CONTENT_TYPE);
  }
}
