// ========================================================================
// Copyright 199-2004 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.opengoss.web.internal.jetty;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.io.Buffer;
import org.mortbay.io.WriterOutputStream;
import org.mortbay.io.nio.NIOBuffer;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.HttpConnection;
import org.mortbay.jetty.HttpContent;
import org.mortbay.jetty.HttpHeaders;
import org.mortbay.jetty.HttpMethods;
import org.mortbay.jetty.InclusiveByteRange;
import org.mortbay.jetty.MimeTypes;
import org.mortbay.jetty.ResourceCache;
import org.mortbay.jetty.Response;
import org.mortbay.jetty.handler.ContextHandler;
import org.mortbay.jetty.nio.NIOConnector;
import org.mortbay.log.Log;
import org.mortbay.resource.Resource;
import org.mortbay.resource.ResourceFactory;
import org.mortbay.util.IO;
import org.mortbay.util.MultiPartOutputStream;
import org.mortbay.util.URIUtil;



/* ------------------------------------------------------------ */
/** The default servlet.                                                 
 * This servlet, normally mapped to /, provides the handling for static 
 * content, OPTION and TRACE methods for the context.                   
 * The following initParameters are supported:                          
 * <PRE>                                                                      
 *   acceptRanges     If true, range requests and responses are         
 *                    supported                                         
 *                                                                      
 *   dirAllowed       If true, directory listings are returned if no    
 *                    welcome file is found. Else 403 Forbidden.        
 *
 *   redirectWelcome  If true, welcome files are redirected rather than
 *                    forwarded to.
 *
 *   gzip             If set to true, then static content will be served as 
 *                    gzip content encoded if a matching resource is 
 *                    found ending with ".gz"
 *
 *  resourceBase      Set to replace the context resource base
 *
 *  relativeResourceBase    
 *                    Set with a pathname relative to the base of the
 *                    servlet context root. Useful for only serving static content out
 *                    of only specific subdirectories.
 * 
 *  aliases           If True, aliases of resources are allowed (eg. symbolic
 *                    links and caps variations). May bypass security constraints.
 *                    
 *  maxCacheSize      The maximum total size of the cache or 0 for no cache.
 *  maxCachedFileSize The maximum size of a file to cache
 *  maxCachedFiles    The maximum number of files to cache
 *  
 *  useFileMappedBuffer 
 *                    If set to true, it will use mapped file buffer to serve static content
 *                    when using NIO connector. Setting this value to false means that
 *                    a direct buffer will be used instead of a mapped file buffer. 
 *                    By default, this is set to true.
 * </PRE>
 *                                                               
 * The MOVE method is allowed if PUT and DELETE are allowed             
 *
 * @author Greg Wilkins (gregw)
 * @author Nigel Canonizado
 */
@SuppressWarnings("serial")
public class DefaultServlet extends HttpServlet implements ResourceFactory
{
    private WebPluginServletContext _context;
    
    private boolean _acceptRanges=true;
    private boolean _dirAllowed=true;
    private boolean _redirectWelcome=true;
    private boolean _gzip=true;
    
    private Resource _resourceBase;
    private ResourceCache _cache;
    
    private MimeTypes _mimeTypes;
    private String[] _welcomes;
    private boolean _aliases=false;
    private boolean _useFileMappedBuffer=false;
    
    
    /* ------------------------------------------------------------ */
    public void init()
    throws UnavailableException
    {
        ServletContext config=getServletContext();
        _context = (WebPluginServletContext)config;
        _mimeTypes = _context.getMimeTypes();
        
        _welcomes = _context.getWelcomeFiles();
        if (_welcomes==null)
            _welcomes=new String[] {"index.jsp","index.html"};
        
        _acceptRanges=getInitBoolean("acceptRanges",_acceptRanges);
        _dirAllowed=getInitBoolean("dirAllowed",_dirAllowed);
        _redirectWelcome=getInitBoolean("redirectWelcome",_redirectWelcome);
        _gzip=getInitBoolean("gzip",_gzip);
        _aliases=getInitBoolean("aliases",_aliases);
        _useFileMappedBuffer=getInitBoolean("useFileMappedBuffer",_useFileMappedBuffer);
        
        String rrb = getInitParameter("relativeResourceBase");
        if (rrb!=null)
        {
            try
            {
                _resourceBase=Resource.newResource(_context.getResource("/")).addPath(rrb);
            }
            catch (Exception e) 
            {
                Log.warn(Log.EXCEPTION,e);
                throw new UnavailableException(e.toString()); 
            }
        }
        
        String rb=getInitParameter("resourceBase");
        if (rrb != null && rb != null)
            throw new  UnavailableException("resourceBase & relativeResourceBase");    
        
        if (rb!=null)
        {
            try{_resourceBase=Resource.newResource(rb);}
            catch (Exception e) {
                Log.warn(Log.EXCEPTION,e);
                throw new UnavailableException(e.toString()); 
            }
        }
        
        try
        {
            if (_resourceBase==null) {
            	_resourceBase=Resource.newResource( _context.getResource("/"));
            }

            int max_cache_size=getInitInt("maxCacheSize", -2);
            if (max_cache_size>0)
            {
                if (_cache==null)
                    _cache=new NIOResourceCache(_mimeTypes);
                _cache.setMaxCacheSize(max_cache_size);    
            }
            else if (max_cache_size!=-2)
                _cache=null;
            
            if (_cache!=null)
            {
                int max_cached_file_size=getInitInt("maxCachedFileSize", -2);
                if (max_cached_file_size>=-1)
                    _cache.setMaxCachedFileSize(max_cached_file_size);    
                
                int max_cached_files=getInitInt("maxCachedFiles", -2);
                if (max_cached_files>=-1)
                    _cache.setMaxCachedFiles(max_cached_files);
                
                _cache.start();
            }
        }
        catch (Exception e) 
        {
            Log.warn(Log.EXCEPTION,e);
            throw new UnavailableException(e.toString()); 
        }
        
        if (Log.isDebugEnabled()) Log.debug("resource base = "+_resourceBase);
    }
    
    /* ------------------------------------------------------------ */
    private boolean getInitBoolean(String name, boolean dft)
    {
        String value=getInitParameter(name);
        if (value==null || value.length()==0)
            return dft;
        return (value.startsWith("t")||
                value.startsWith("T")||
                value.startsWith("y")||
                value.startsWith("Y")||
                value.startsWith("1"));
    }
    
    /* ------------------------------------------------------------ */
    private int getInitInt(String name, int dft)
    {
        String value=getInitParameter(name);
        if (value!=null && value.length()>0)
            return Integer.parseInt(value);
        return dft;
    }
    
    /* ------------------------------------------------------------ */
    /** get Resource to serve.
     * Map a path to a resource. The default implementation calls
     * HttpContext.getResource but derived servlets may provide
     * their own mapping.
     * @param pathInContext The path to find a resource for.
     * @return The resource to serve.
     */
    public Resource getResource(String pathInContext)
    {
        if (_resourceBase==null)
            return null;
        Resource r=null;
        try
        {
            r = _resourceBase.addPath(pathInContext);
            if (!_aliases && r.getAlias()!=null)
            {
                if (r.exists())
                    Log.warn("Aliased resource: "+r+"=="+r.getAlias());
                return null;
            }
            if (Log.isDebugEnabled()) Log.debug("RESOURCE="+r);
        }
        catch (IOException e)
        {
            Log.ignore(e);
        }
        return r;
    }
    
    /* ------------------------------------------------------------ */
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
    	throws ServletException, IOException
    {
        String servletPath=null;
        String pathInfo=null;
        Enumeration reqRanges = null;
        Boolean included =(Boolean)request.getAttribute(DefaultDispatcher.__INCLUDE_JETTY);
        if (included!=null && included.booleanValue())
        {
            servletPath=(String)request.getAttribute(DefaultDispatcher.__INCLUDE_SERVLET_PATH);
            pathInfo=(String)request.getAttribute(DefaultDispatcher.__INCLUDE_PATH_INFO);
            if (servletPath==null)
            {
                servletPath=request.getServletPath();
                pathInfo=request.getPathInfo();
            }
        }
        else
        {
            included=Boolean.FALSE;
            servletPath=request.getServletPath();
            pathInfo=request.getPathInfo();
            
            // Is this a range request?
            reqRanges = request.getHeaders(HttpHeaders.RANGE);
            if (reqRanges!=null && !reqRanges.hasMoreElements())
                reqRanges=null;
        }
        
        String pathInContext=URIUtil.addPaths(servletPath,pathInfo);
        boolean endsWithSlash=pathInContext.endsWith("/");
        

        // Can we gzip this request?
        String pathInContextGz=null;
        boolean gzip=false;
        if (_gzip && reqRanges==null && !endsWithSlash )
        {
            String accept=request.getHeader(HttpHeaders.ACCEPT_ENCODING);
            if (accept!=null && accept.indexOf("gzip")>=0)
                gzip=true;
        }
        
        // Find the resource and content
        Resource resource=null;
        HttpContent content=null;
        try
        {   
            // Try gzipped content first
            if (gzip)
            {
                pathInContextGz=pathInContext+".gz";  // TODO grrrr - hate having to do this every request!
                if (_cache==null)
                    resource=getResource(pathInContextGz);
                else
                {
                    content=_cache.lookup(pathInContextGz,this);
                    if (content!=null)
                        resource=content.getResource();
                    else
                        resource=getResource(pathInContextGz);
                }
            }
            //String path=null;     
            // If we found a gzipped resource
            if (resource!=null && resource.exists())
            {
            	//TODO: BUG
                //path=pathInContextGz;
            }
            else
            {
                // Look for a normal resource
                pathInContextGz=null;
                gzip=false;
                //TODO: BUG
                //path=pathInContext;
                
                // look for normal resource
                if (_cache==null)
                    resource=getResource(pathInContext);
                else
                {
                    content=_cache.lookup(pathInContext,this);
                    
                    if (content!=null)
                        resource=content.getResource();
                    else
                        resource=getResource(pathInContext);
                }
            }
            
            if (Log.isDebugEnabled())
                Log.debug("resource="+resource+(content!=null?" content":"")+(gzip?" gzip":""));
                        
            // Handle resource
            if (resource==null || !resource.exists())
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            else if (!resource.isDirectory())
            {
                // Handle gzipping
                if (gzip)
                {
                   response.setHeader(HttpHeaders.CONTENT_ENCODING,"gzip");
                   response.setHeader(HttpHeaders.CONTENT_TYPE,_context.getMimeType(pathInContext));
                }
                
                // ensure we have content
                if (content==null)
                    content=new UnCachedContent(resource);
                
                if (included.booleanValue() || passConditionalHeaders(request,response, resource,content))  
                    sendData(request,response,included.booleanValue(),resource,content,reqRanges);   
            }
            else
            {
                String welcome=null;
                
                if (!endsWithSlash && !pathInContext.equals("/"))
                {
                    StringBuffer buf=request.getRequestURL();
                    buf.append('/');
                    String q=request.getQueryString();
                    if (q!=null&&q.length()!=0)
                    {
                        buf.append('?');
                        buf.append(q);
                    }
                    response.setContentLength(0);
                    response.sendRedirect(response.encodeRedirectURL(buf.toString()));
                }
                // else look for a welcome file
                else if (null!=(welcome=getWelcomeFile(resource)))
                {
                    String ipath=URIUtil.addPaths(pathInContext,welcome);
                    if (_redirectWelcome)
                    {
                        // Redirect to the index
                        response.setContentLength(0);
                        String q=request.getQueryString();
                        if (q!=null&&q.length()!=0)
                            response.sendRedirect(URIUtil.addPaths( _context.getContextPath(),ipath)+"?"+q);
                        else
                            response.sendRedirect(URIUtil.addPaths( _context.getContextPath(),ipath));
                    }
                    else
                    {
                        // Forward to the index
                        RequestDispatcher dispatcher=request.getRequestDispatcher(ipath);
                        if (dispatcher!=null)
                        {
                            if (included.booleanValue())
                                dispatcher.include(request,response);
                            else
                                dispatcher.forward(request,response);
                        }
                    }
                }
                else 
                {
                    content=new UnCachedContent(resource);
                    if (included.booleanValue() || passConditionalHeaders(request,response, resource,content))
                        sendDirectory(request,response,resource,pathInContext.length()>1);
                }
            }
        }
        catch(IllegalArgumentException e)
        {
            Log.warn(Log.EXCEPTION,e);
        }
        finally
        {
            if (content!=null)
                content.release();
        }
        
    }
    
    /* ------------------------------------------------------------ */
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        doGet(request,response);
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Finds a matching welcome file for the supplied {@link Resource}. This will be the first entry in the list of 
     * configured {@link #_welcomes welcome files} that existing within the directory referenced by the <code>Resource</code>.
     * If the resource is not a directory, or no matching file is found, then <code>null</code> is returned.
     * The list of welcome files is read from the {@link ContextHandler} for this servlet, or
     * <code>"index.jsp" , "index.html"</code> if that is <code>null</code>.
     * @param resource
     * @return The name of the matching welcome file.
     * @throws IOException
     * @throws MalformedURLException
     */
    private String getWelcomeFile(Resource resource) throws MalformedURLException, IOException
    {
        if (!resource.isDirectory() || _welcomes==null)
            return null;

        for (int i=0;i<_welcomes.length;i++)
        {
            Resource welcome=resource.addPath(_welcomes[i]);
            if (welcome.exists())
                return _welcomes[i];
        }

        return null;
    }

    /* ------------------------------------------------------------ */
    /* Check modification date headers.
     */
    protected boolean passConditionalHeaders(HttpServletRequest request,HttpServletResponse response, Resource resource, HttpContent content)
    throws IOException
    {
        if (!request.getMethod().equals(HttpMethods.HEAD) )
        {
            if (content!=null)
            {
                String ifms=request.getHeader(HttpHeaders.IF_MODIFIED_SINCE);
   
                Buffer mdlm=content.getLastModified();
                if (ifms!=null && mdlm!=null && ifms.equals(mdlm.toString()))
                {
                    response.reset();
                    response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                    response.flushBuffer();
                    return false;
                }
            }

            // Parse the if[un]modified dates and compare to resource
            long date=0;
            
            if ((date=request.getDateHeader(HttpHeaders.IF_UNMODIFIED_SINCE))>0)
            {
                if (resource.lastModified()/1000 > date/1000)
                {
                    response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                    return false;
                }
            }
            
            if ((date=request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE))>0)
            {
                if (resource.lastModified()/1000 <= date/1000)
                {
                    response.reset();
                    response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                    response.flushBuffer();
                    return false;
                }
            }
        }
        return true;
    }
    
    
    /* ------------------------------------------------------------------- */
    protected void sendDirectory(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Resource resource,
                                 boolean parent)
    throws IOException
    {
        if (!_dirAllowed)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        
        byte[] data=null;
        String base = URIUtil.addPaths(request.getRequestURI(),"/");
        String dir = resource.getListHTML(base,parent);
        if (dir==null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
            "No directory");
            return;
        }
        
        // TODO cache this?
        data=dir.getBytes("UTF-8");
        response.setContentType("text/html; charset=UTF-8");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }
    
    
    /* ------------------------------------------------------------ */
    protected void sendData(HttpServletRequest request,
                            HttpServletResponse response,
                            boolean include,
                            Resource resource,
                            HttpContent content,
                            Enumeration reqRanges)
    throws IOException
    {
        long content_length=resource.length();
        
        // Get the output stream (or writer)
        OutputStream out =null;
        try{out = response.getOutputStream();}
        catch(IllegalStateException e) {out = new WriterOutputStream(response.getWriter());}
        
        if ( reqRanges == null || !reqRanges.hasMoreElements())
        {
            //  if there were no ranges, send entire entity
            if (include)
            {
                resource.writeTo(out,0,content_length);
            }
            else
            {
                // See if a short direct method can be used?
                if (out instanceof HttpConnection.Output)
                {
                    ((HttpConnection.Output)out).sendContent(content);
                }
                else
                {
                    // Write content normally
                    writeHeaders(response,content,content_length);
                    resource.writeTo(out,0,content_length);
                }
            }
        }
        else
        {
            
            // Parse the satisfiable ranges
            List ranges =InclusiveByteRange.satisfiableRanges(reqRanges,content_length);
            
            //  if there are no satisfiable ranges, send 416 response
            if (ranges==null || ranges.size()==0)
            {
                writeHeaders(response, content, content_length);
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, 
                        InclusiveByteRange.to416HeaderRangeString(content_length));
                resource.writeTo(out,0,content_length);
                return;
            }
            
            
            //  if there is only a single valid range (must be satisfiable 
            //  since were here now), send that range with a 216 response
            if ( ranges.size()== 1)
            {
                InclusiveByteRange singleSatisfiableRange =
                    (InclusiveByteRange)ranges.get(0);
                long singleLength = singleSatisfiableRange.getSize(content_length);
                writeHeaders(response,content,singleLength);
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader(HttpHeaders.CONTENT_RANGE, 
                        singleSatisfiableRange.toHeaderRangeString(content_length));
                resource.writeTo(out,singleSatisfiableRange.getFirst(content_length),singleLength);
                return;
            }
            
            
            //  multiple non-overlapping valid ranges cause a multipart
            //  216 response which does not require an overall 
            //  content-length header
            //
            writeHeaders(response,content,-1);
            String mimetype=content.getContentType().toString();
            MultiPartOutputStream multi = new MultiPartOutputStream(out);
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            
            // If the request has a "Request-Range" header then we need to
            // send an old style multipart/x-byteranges Content-Type. This
            // keeps Netscape and acrobat happy. This is what Apache does.
            String ctp;
            if (request.getHeader(HttpHeaders.REQUEST_RANGE)!=null)
                ctp = "multipart/x-byteranges; boundary=";
            else
                ctp = "multipart/byteranges; boundary=";
            response.setContentType(ctp+multi.getBoundary());
            
            InputStream in=resource.getInputStream();
            long pos=0;
            
            for (int i=0;i<ranges.size();i++)
            {
                InclusiveByteRange ibr = (InclusiveByteRange) ranges.get(i);
                String header=HttpHeaders.CONTENT_RANGE+": "+
                ibr.toHeaderRangeString(content_length);
                multi.startPart(mimetype,new String[]{header});
                
                long start=ibr.getFirst(content_length);
                long size=ibr.getSize(content_length);
                if (in!=null)
                {
                    // Handle non cached resource
                    if (start<pos)
                    {
                        in.close();
                        in=resource.getInputStream();
                        pos=0;
                    }
                    if (pos<start)
                    {
                        in.skip(start-pos);
                        pos=start;
                    }
                    IO.copy(in,multi,size);
                    pos+=size;
                }
                else
                    // Handle cached resource
                    (resource).writeTo(multi,start,size);
                
            }
            if (in!=null)
                in.close();
            multi.close();
        }
        return;
    }
    
    /* ------------------------------------------------------------ */
    protected void writeHeaders(HttpServletResponse response,HttpContent content,long count)
    throws IOException
    {   
        // TODO - use the buffers
        
        if (content.getContentType()!=null)
            response.setContentType(content.getContentType().toString());
        if (content.getLastModified()!=null)	
            response.setHeader(HttpHeaders.LAST_MODIFIED,content.getLastModified().toString());
        else
            response.setDateHeader(HttpHeaders.LAST_MODIFIED,content.getResource().lastModified());
        
        if (count != -1)
        {
            if (response instanceof Response)
                ((Response)response).setLongContentLength(count);
            else if (count<Integer.MAX_VALUE)
                response.setContentLength((int)count);
            else 
                response.setHeader(HttpHeaders.CONTENT_LENGTH,""+count);
        }
        
        if (_acceptRanges)
            response.setHeader(HttpHeaders.ACCEPT_RANGES,"bytes");
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.Servlet#destroy()
     */
    public void destroy()
    {
        try
        {
            if (_cache!=null)
                _cache.stop();
        }
        catch(Exception e)
        {
            Log.warn(Log.EXCEPTION,e);
        }
        finally
        {
            super.destroy();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class UnCachedContent implements HttpContent
    {
        Resource _resource;
        
        UnCachedContent(Resource resource)
        {
            _resource=resource;
        }
        
        /* ------------------------------------------------------------ */
        public Buffer getContentType()
        {
            return _mimeTypes.getMimeByExtension(_resource.toString());
        }

        /* ------------------------------------------------------------ */
        public Buffer getLastModified()
        {
            return null;
        }

        /* ------------------------------------------------------------ */
        public Buffer getBuffer()
        {
            return null;
        }

        /* ------------------------------------------------------------ */
        public long getContentLength()
        {
            return _resource.length();
        }

        /* ------------------------------------------------------------ */
        public InputStream getInputStream() throws IOException
        {
            return _resource.getInputStream();
        }

        /* ------------------------------------------------------------ */
        public Resource getResource()
        {
            return _resource;
        }

        /* ------------------------------------------------------------ */
        public void release()
        {
            _resource.release();
            _resource=null;
        }
        
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class NIOResourceCache extends ResourceCache
    {

        /* ------------------------------------------------------------ */
        public NIOResourceCache(MimeTypes mimeTypes)
        {
            super(mimeTypes);
        }

        /* ------------------------------------------------------------ */
        protected void fill(Content content) throws IOException
        {
            super.fill(content);
            
            Connector connector = HttpConnection.getCurrentConnection().getConnector();
            Buffer buffer=null;
            Resource resource=content.getResource();
            long length=resource.length();
            if (connector instanceof NIOConnector) 
            {
            	//TODO
            	_useFileMappedBuffer = false;
                if (_useFileMappedBuffer) 
                {    
                    File file = resource.getFile();
                    if (file != null) 
                        buffer = new NIOBuffer(file);
                } 
                else 
                {
                    InputStream is = (resource.getURL().openStream());
                    buffer = new NIOBuffer((int) length, ((NIOConnector)connector).getUseDirectBuffers()?NIOBuffer.DIRECT:NIOBuffer.INDIRECT);
                    buffer.readFrom(is,(int)length);
                    is.close();
                }
            } 
            else 
            {
                super.fill(content);
            }   
            
            content.setBuffer(buffer);
        }
    }
}
