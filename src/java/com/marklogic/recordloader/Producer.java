/**
 * Copyright (c) 2006 Mark Logic Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The use of the Apache License does not indicate that this project is
 * affiliated with the Apache Software Foundation.
 */
package com.marklogic.recordloader;

import java.io.IOException;
import java.io.InputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import com.marklogic.ps.SimpleLogger;
import com.marklogic.ps.Utilities;
import com.marklogic.xcc.exceptions.UnimplementedFeatureException;

/**
 * @author Michael Blakeley, michael.blakeley@marklogic.com
 * 
 */
public class Producer extends InputStream {

    private String outputEncoding = Configuration.OUTPUT_ENCODING_DEFAULT;

    private SimpleLogger logger;

    private XmlPullParser xpp;

    private String recordName;

    private String recordNamespace;

    private StringBuffer buffer;

    private byte[] byteBuffer;

    private Configuration config;

    private String idName;

    private boolean skippingRecord = false;

    private long bytesRead = 0;

    private String currentId = null;

    private int byteIndex = 0;

    private boolean keepGoing = true;

    private boolean copyNamespaceDeclarations = true;

    private boolean startOfRecord = true;

    /**
     * @param _config
     * @param _xpp
     */
    public Producer(Configuration _config, XmlPullParser _xpp) {
        config = _config;
        xpp = _xpp;

        idName = config.getIdNodeName();
        recordNamespace = config.getRecordNamespace();
        recordName = config.getRecordName();

        copyNamespaceDeclarations = config.isCopyNamespaceDeclarations();

        logger = _config.getLogger();
        logger.fine("recordName=" + recordName);
    }

    /**
     * @throws XmlPullParserException
     * 
     */
    private void handleRecordStart() throws XmlPullParserException {

        // handle automatic id generation here
        String newId;
        boolean useAutomaticIds = config.isUseAutomaticIds();
        boolean useFileNameIds = config.isUseFileNameIds();
        if (useAutomaticIds || useFileNameIds || idName.startsWith("@")) {
            if (useAutomaticIds) {
                // automatic ids, starting from 1
                // config uses a synchronized sequence of long
                newId = config.getAutoId();
                logger.fine("automatic document id " + newId);
            } else if (useFileNameIds) {
                // the constructor had better have set our id!
                // note that skipping won't work for this case
                if (null == currentId) {
                    throw new UnimplementedFeatureException(
                            "Cannot use filename ids unless the constructor sets currentId");
                }
                logger.fine("using filename id " + currentId);
                newId = currentId;
            } else {
                // if the idName starts with @, it's an attribute
                // handle attributes as idName
                if (xpp.getAttributeCount() < 1) {
                    throw new XmlPullParserException(
                            "found no attributes for recordName = "
                                    + recordName + ", idName=" + idName
                                    + " at "
                                    + xpp.getPositionDescription());
                }
                // try with and without a namespace: first, try without
                newId = xpp.getAttributeValue("", idName.substring(1));
                if (newId == null) {
                    newId = xpp.getAttributeValue(recordNamespace, idName
                            .substring(1));
                }
                if (newId == null) {
                    throw new XmlPullParserException("null id " + idName
                            + " at " + xpp.getPositionDescription());
                }
                logger.fine("found id " + idName + " = " + newId);
            }

            setCurrentId(newId);
        }
    }

    private void processStartElement() throws IOException,
            XmlPullParserException {
        String name = xpp.getName();
        String namespace = xpp.getNamespace();
        boolean isEmpty = xpp.isEmptyElementTag();

        logger.finest("name = " + name);
        String text = xpp.getText();
        logger.finest("text = " + text);
        // guard against and work around a known bug
        if (!text.contains(name)) {
            logger.warning("working around xpp3 bug 249: name = " + name
                    + ", text = " + text);
            text = "<" + name;
            int attributeCount = xpp.getAttributeCount();
            String aPrefix;
            if (attributeCount > 0) {
                for (int i = 0; i < attributeCount; i++) {
                    aPrefix = xpp.getAttributePrefix(i);
                    text += " "
                            + (null == aPrefix ? "" : (aPrefix + ":"))
                            + xpp.getAttributeName(i)
                            + "=\""
                            + Utilities.escapeXml(xpp
                                    .getAttributeValue(i)) + "\"";
                }
            }
            text += (isEmpty ? "/>" : ">");
        }

        boolean isRecordRoot = false;

        if (name.equals(recordName) && namespace.equals(recordNamespace)) {
            isRecordRoot = true;
            handleRecordStart();
        }

        // allow for repeated idName elements: first one wins
        // NOTE: idName is namespace-insensitive
        if (null == currentId && name.equals(idName)) {
            // pick out the contents and use it for the uri
            if (xpp.nextToken() != XmlPullParser.TEXT) {
                throw new XmlPullParserException(
                        "badly formed xml: missing id at "
                                + xpp.getPositionDescription());
            }

            String newId = xpp.getText();
            logger.fine("found id " + idName + " = " + newId);

            // now we can set currentId
            setCurrentId(newId);

            // now we know that we'll use this content and id
            write(text);
            write(currentId);

            // advance xpp to the END_ELEMENT - brittle?
            if (xpp.nextToken() != XmlPullParser.END_TAG) {
                throw new XmlPullParserException(
                        "badly formed xml: no END_TAG after id text"
                                + xpp.getPositionDescription());
            }
            text = xpp.getText();
            logger.finest("END_TAG = " + text);
            write(text);
            return;
        }

        // if the startId is still defined, and the uri has been found,
        // we should skip as much of this work as possible
        // this avoids OutOfMemory errors, too
        if (skippingRecord) {
            logger.finest("skipping record");
            return;
        }

        // this seems to be the only way to handle empty elements:
        // write it as a end-element, only.
        // note that attributes are still ok in this case
        if (isEmpty) {
            logger.finest("empty element");
            return;
        }

        if (copyNamespaceDeclarations && isRecordRoot) {
            // preserve namespace declarations into this element
            int depth = xpp.getDepth();
            if (depth > 0) {
                int stop = xpp.getNamespaceCount(depth - 1);
                if (stop > 0) {
                    StringBuffer decl = null;
                    String nsDeclPrefix, nsDeclUri;
                    logger.finer("checking namespace declarations");
                    for (int i = 0; i < stop; i++) {
                        if (decl == null) {
                            decl = new StringBuffer();
                        }
                        nsDeclPrefix = xpp.getNamespacePrefix(i);
                        nsDeclUri = xpp.getNamespaceUri(i);
                        logger.finest("found namespace declaration "
                                + nsDeclPrefix + " = " + nsDeclUri);
                        decl.append(" xmlns");
                        if (nsDeclPrefix != null) {
                            decl.append(":");
                            decl.append(nsDeclPrefix);
                        }
                        decl.append("=\"");
                        decl.append(nsDeclUri);
                        decl.append("\"");
                    }
                    // copy the namespace decls to the end of the tag
                    if (decl != null) {
                        logger.finer("copying namespace declarations");
                        text = text.replaceFirst(">$", decl.toString()
                                + (isEmpty ? "/" : "") + ">");
                    }
                } else {
                    logger.finer("no namespace declarations to copy");
                }
            } else {
                logger.finer("no namespace declarations to copy at "
                        + depth);
            }
        }

        logger.finest("writing text = " + text);
        write(text);
        return;
    }

    private boolean processEndElement() throws XmlPullParserException {
        // NOTE: must return false when the record end-element is found

        String name = xpp.getName();
        String namespace = xpp.getNamespace();
        logger.finest("name = " + name);

        // record the element text
        if (!skippingRecord) {
            write(xpp.getText());
        }

        if (!(recordName.equals(name) && recordNamespace
                .equals(namespace))) {
            // not the end of the record: go look for more nodes
            return true;
        }

        // end of record: were we skipping?
        if (skippingRecord) {
            logger.fine("reached the end of skipped record");
            return false;
        }

        // did something go wrong?
        if (currentId == null) {
            throw new XmlPullParserException("end of record element "
                    + name + " with no id found: "
                    + Configuration.ID_NAME_KEY + "=" + idName);
        }

        // end of record
        logger.fine("end of record");
        //logger.finest(buffer.toString()); // DEBUG
        return false;
    }

    /**
     * @param string
     */
    private void write(String string) {
        // if the startId is still defined, and the uri has been found,
        // we should skip as much of this work as possible
        // this avoids OutOfMemory too
        if (skippingRecord) {
            return;
        }

        if (buffer == null) {
            buffer = new StringBuffer();
        }

        //logger.finest("string = " + string); // DEBUG
        buffer.append(string);
    }

    /**
     * @return
     */
    public long getBytesRead() {
        return bytesRead;
    }

    public String getCurrentId() throws XmlPullParserException,
            IOException {
        if (currentId == null) {
            logger.finer("parsing for id");
            while (keepGoing && currentId == null) {
                processNext();
            }
        }

        logger.fine(currentId);
        return currentId;
    }

    public void setCurrentId(String _id) {
        currentId = _id;
    }

    public boolean isSkippingRecord() {
        return skippingRecord;
    }

    private int readByteBuffer(int _readSize) throws IOException {
        // do we have something ready to read?
        if (byteBuffer != null) {
            if (byteIndex < byteBuffer.length) {
                logger.finer("existing = " + getByteBufferDescription());
                return byteBuffer.length - byteIndex;
            }
            byteBuffer = null;
            buffer = null;
        }

        if (buffer == null) {
            logger.fine("buffer is null");
            byteBuffer = null;
            // must wrap any non-IOException in an IOException
            try {
                while (keepGoing
                        && (buffer == null || buffer.length() < _readSize)) {
                    processNext();
                }
            } catch (XmlPullParserException e) {
                IOException ioe = new IOException();
                ioe.initCause(e);
                throw ioe;
            }
        }

        if (buffer == null) {
            // indicate EOF
            logger.fine("EOF");
            return -1;
        }

        if (byteBuffer == null) {
            // get more bytes
            byteBuffer = buffer.toString().getBytes(outputEncoding);
            byteIndex = 0;
        }

        //logger.fine("new = " + getByteBufferDescription()); // DEBUG
        return byteBuffer.length - byteIndex;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.io.InputStream#read()
     */
    @Override
    public int read() throws IOException {
        // read and return the next byte
        int available = readByteBuffer(1);

        if (available < 0) {
            return available;
        }

        bytesRead++;
        return byteBuffer[byteIndex++];
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len < 1) {
            return len;
        }

        int available = readByteBuffer(len - 1);
        // DEBUG
        //logger.fine("off = " + off + ", len = " + len + ", avail = " + available);

        if (available < 0) {
            return available;
        }

        // copy byte buffer into target buffer
        int copyLen = Math.min(available, len);
        System.arraycopy(byteBuffer, byteIndex, b, off, copyLen);
        byteIndex += copyLen;
        bytesRead += copyLen;

        return copyLen;
    }

    /**
     * @return
     * @throws XmlPullParserException
     * @throws IOException
     * 
     */
    private void processNext() throws XmlPullParserException, IOException {
        if (!keepGoing) {
            return;
        }

        if (startOfRecord) {
            // this is the start of the record
            // by definition, we are at the start of an element
            logger.fine("processing start of record");
            processStartElement();
            startOfRecord = false;
            return;
        }

        int eventType;
        try {
            // NOTE: next() skips comments, ignorable-whitespace, etc.
            // to catch these, use nextToken() instead.
            eventType = xpp.nextToken();
            logger.finest("eventType = " + eventType);
            switch (eventType) {
            case XmlPullParser.START_TAG:
                processStartElement();
                break;
            case XmlPullParser.TEXT:
                write(Utilities.escapeXml(xpp.getText()));
                break;
            case XmlPullParser.CDSECT:
                // round-trip it
                write("<![CDATA[");
                write(xpp.getText());
                write("]]>");
                break;
            case XmlPullParser.IGNORABLE_WHITESPACE:
                write(xpp.getText());
                break;
            case XmlPullParser.ENTITY_REF:
                write("&");
                write(xpp.getName());
                write(";");
                break;
            case XmlPullParser.DOCDECL:
                write("<!DOCTYPE");
                write(xpp.getText());
                write(">");
                break;
            case XmlPullParser.PROCESSING_INSTRUCTION:
                write("<?");
                write(xpp.getText());
                write("?>");
                break;
            case XmlPullParser.COMMENT:
                write("<!--");
                write(xpp.getText());
                write("-->");
                break;
            case XmlPullParser.END_TAG:
                keepGoing = processEndElement();
                break;
            case XmlPullParser.START_DOCUMENT:
                throw new XmlPullParserException(
                        "unexpected start of document within record!\n"
                                + "recordName = " + recordName
                                + ", recordNamespace = "
                                + recordNamespace + " at "
                                + xpp.getPositionDescription());
            case XmlPullParser.END_DOCUMENT:
                throw new XmlPullParserException(
                        "end of document before end of current record!\n"
                                + "recordName = " + recordName
                                + ", recordNamespace = "
                                + recordNamespace + " at "
                                + xpp.getPositionDescription());
            default:
                throw new XmlPullParserException("UNIMPLEMENTED: "
                        + eventType);
            }
        } catch (XmlPullParserException e) {
            logger.warning(e.getClass().getSimpleName() + " at "
                    + xpp.getPositionDescription());
            if (e.getMessage().contains("quotation or apostrophe")
                    && config.isFullRepair()) {
                // messed-up attribute? skip it?
                logger.warning("attribute error: " + e.getMessage());
                // all we can do is ignore it, apparently
            } else {
                throw e;
            }
        }
    }

    /**
     * @param b
     * @throws IOException
     * @throws XmlPullParserException
     */
    public void setSkippingRecord(boolean b)
            throws XmlPullParserException, IOException {
        skippingRecord = b;
        logger.finest("skippingRecord = " + skippingRecord);

        // spool out the rest of the record
        while (skippingRecord && keepGoing) {
            processNext();
        }
    }

    public String getByteBufferDescription() {
        if (byteBuffer == null) {
            return "" + byteIndex + " in empty byteBuffer";
        }
        return "" + byteIndex + "/" + byteBuffer.length + " of "
                + new String(byteBuffer);
    }

    /**
     * @return
     */
    public String getBuffer() {
        return (null != buffer) ? buffer.toString() : null;
    }

}
