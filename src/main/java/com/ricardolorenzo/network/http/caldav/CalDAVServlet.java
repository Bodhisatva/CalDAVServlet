/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.ricardolorenzo.network.http.caldav;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ricardolorenzo.network.http.caldav.locking.ResourceLocksMap;
import com.ricardolorenzo.network.http.caldav.method.ACL;
import com.ricardolorenzo.network.http.caldav.method.COPY;
import com.ricardolorenzo.network.http.caldav.method.DELETE;
import com.ricardolorenzo.network.http.caldav.method.GET;
import com.ricardolorenzo.network.http.caldav.method.HEAD;
import com.ricardolorenzo.network.http.caldav.method.LOCK;
import com.ricardolorenzo.network.http.caldav.method.MKCALENDAR;
import com.ricardolorenzo.network.http.caldav.method.MKCOL;
import com.ricardolorenzo.network.http.caldav.method.MOVE;
import com.ricardolorenzo.network.http.caldav.method.NOT_IMPLEMENTED;
import com.ricardolorenzo.network.http.caldav.method.OPTIONS;
import com.ricardolorenzo.network.http.caldav.method.PROPFIND;
import com.ricardolorenzo.network.http.caldav.method.PROPPATCH;
import com.ricardolorenzo.network.http.caldav.method.PUT;
import com.ricardolorenzo.network.http.caldav.method.REPORT;
import com.ricardolorenzo.network.http.caldav.method.UNLOCK;
import com.ricardolorenzo.network.http.caldav.session.CalDAVTransaction;
import com.ricardolorenzo.network.http.caldav.store.CalDAVStore;

/**
 * 
 * @author Ricardo Lorenzo
 * 
 */
public class CalDAVServlet extends HttpServlet {
	private final Logger logger = LoggerFactory.getLogger(getClass());
    private static final long serialVersionUID = 7073432765018098252L;

    /**
     * MD5 message digest provider.
     */
    protected static MessageDigest MD5;

    private ResourceLocksMap resourceLocks;
    private CalDAVStore store;
    private Map<String, CalDAVMethod> httpMethods;

    /**
     * CalDAVServlet constructor
     */
    public CalDAVServlet() {
        this.resourceLocks = new ResourceLocksMap();
        this.httpMethods = new HashMap<String, CalDAVMethod>();

        try {
            MD5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
        	logger.error("MD5", e);
            throw new IllegalStateException();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.servlet.GenericServlet#init(javax.servlet.ServletConfig)
     */
    public void init(ServletConfig conf) throws ServletException {
        File root = new File("/home");
        boolean lazyFolderCreation = false;
        int no_content_length_headers = 0;
        String instead_of_404 = null;
        String initParameter = conf.getInitParameter("store");
		if (initParameter == null) {
            throw new ServletException("store parameter not found");
        }
        String default_index_file = conf.getInitParameter("default-index-file");
        if (conf.getInitParameter("lazy-folder-creation") != null) {
            try {
                if (Integer.parseInt(conf.getInitParameter("lazy-folder-creation")) == 1) {
                    lazyFolderCreation = true;
                }
            } catch (NumberFormatException e) {
                // nothing
            }
        }
        if (conf.getInitParameter("no-content-length-headers") != null) {
            try {
                no_content_length_headers = Integer.parseInt(conf.getInitParameter("no-content-length-headers"));
            } catch (NumberFormatException e) {
            	logger.warn("Invalid value for no-content-length-headers" + no_content_length_headers, e);
                // nothing
            }
        }
        if (conf.getInitParameter("root") != null) {
            root = new File(conf.getInitParameter("root"));
        }
        if (conf.getInitParameter("instead-of-404") != null) {
            instead_of_404 = conf.getInitParameter("instead-of-404");
        }

        try {
            @SuppressWarnings("rawtypes")
            java.lang.reflect.Constructor c = Class.forName(initParameter).getConstructor(
                    new Class[] { java.io.File.class });
            this.store = (CalDAVStore) c.newInstance(new Object[] { root });
        } catch (ClassNotFoundException e) {
        	logger.error("class=" + initParameter, e);
            throw new ServletException("java class not found [" + initParameter + "]");
        } catch (Exception e) {
        	logger.error("class="+ initParameter, e);
            throw new ServletException("java class cannot be loaded [" + initParameter + "]: "
                    + e.toString());
        }

        CalDAVMimeType mimeType = new CalDAVMimeType() {
            public String getMimeType(String path) {
                return "text/xml";
            }
        };

        DELETE delete;
        COPY copy;
        MKCOL mkcol;

        addMethod("ACL", new ACL(this.store, this.resourceLocks));
        addMethod("GET", new GET(this.store, default_index_file, instead_of_404, this.resourceLocks, mimeType,
                no_content_length_headers));
        addMethod("HEAD", new HEAD(this.store, default_index_file, instead_of_404, this.resourceLocks, mimeType,
                no_content_length_headers));
        delete = (DELETE) addMethod("DELETE", new DELETE(this.store, this.resourceLocks));
        copy = (COPY) addMethod("COPY", new COPY(this.store, this.resourceLocks, delete));
        addMethod("LOCK", new LOCK(this.store, this.resourceLocks));
        addMethod("UNLOCK", new UNLOCK(this.store, this.resourceLocks));
        addMethod("MOVE", new MOVE(this.resourceLocks, delete, copy));
        mkcol = (MKCOL) addMethod("MKCOL", new MKCOL(this.store, this.resourceLocks));
        addMethod("OPTIONS", new OPTIONS(this.store, this.resourceLocks));
        addMethod("PUT", new PUT(this.store, this.resourceLocks, lazyFolderCreation));
        addMethod("PROPFIND", new PROPFIND(this.store, this.resourceLocks, mimeType));
        addMethod("PROPPATCH", new PROPPATCH(this.store, this.resourceLocks));
        addMethod("MKCALENDAR", new MKCALENDAR(this.store, this.resourceLocks, mkcol));
        addMethod("REPORT", new REPORT(this.store, this.resourceLocks));
        addMethod("*", new NOT_IMPLEMENTED());
    }

    private CalDAVMethod addMethod(String method_name, CalDAVMethod method) {
        this.httpMethods.put(method_name, method);
        return method;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest,
     * javax.servlet.http.HttpServletResponse)
     */
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String methodName = req.getMethod();
        CalDAVTransaction transaction = null;
        boolean rollback = false;

        try {
            transaction = this.store.begin(req.getUserPrincipal());
            rollback = true;
            this.store.checkAuthentication(transaction);
            resp.setStatus(CalDAVResponse.SC_OK);

            try {
                CalDAVMethod method = this.httpMethods.get(methodName);
                if (method == null) {
                    method = this.httpMethods.get("*");
                }
                method.execute(transaction, req, resp);
                this.store.commit(transaction);
                rollback = false;
            } catch (IOException e) { 
            	logger.error("methodName=" + methodName, e);
                resp.sendError(CalDAVResponse.SC_INTERNAL_SERVER_ERROR);
                this.store.rollback(transaction);
                throw new ServletException(e);
            }
        } catch (UnauthenticatedException e) {
            resp.sendError(CalDAVResponse.SC_FORBIDDEN);
        } catch (Exception e) {
        	logger.error("methodName=" + methodName, e);
            throw new ServletException(e);
        } finally {
            if (rollback) {
                this.store.rollback(transaction);
            }
        }
    }
}