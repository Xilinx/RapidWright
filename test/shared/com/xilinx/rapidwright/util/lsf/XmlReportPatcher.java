/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.util.lsf;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Junit 5 generates XML reports that have multiple system-out and system-err elements inside testcases.
 * Jenkins parses only the first one.
 *
 * This class patches the xml output to align with Jenkins' expectation.
 */
public class XmlReportPatcher {


    /**
     * Patch all documents in a directory
     */
    public static void fixOutputXmls(Path path) throws IOException {
        try (Stream<Path> files = Files.list(path)) {
            files
                    .filter(p -> p.toString().endsWith(".xml"))
                    .forEach(XmlReportPatcher::fixOutputXml);
        }

    }

    /**
     * Patch one document
     */
    private static void fixOutputXml(Path path) {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

        Document doc;
        try (InputStream is = Files.newInputStream(path)) {

            // parse XML file
            DocumentBuilder db = dbf.newDocumentBuilder();

            // read from a project's resources folder
            doc = db.parse(is);
        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new RuntimeException(e);
        }

        fixOutputXmlDoc(doc);

        try (OutputStream os = Files.newOutputStream(path)) {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(os);

            transformer.transform(source, result);
        } catch (IOException | TransformerException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Patch a in-memory document
     * @param node
     */
    private static void fixOutputXmlDoc(Node node) {
        if (node.getNodeType()==Node.ELEMENT_NODE && ((Element)node).getTagName().equals("testcase")) {
            Node seenStdout = null;
            Node seenStderr = null;
            for (int i=0; i< node.getChildNodes().getLength();i++) {
                Node currentChild = node.getChildNodes().item(i);
                if (currentChild.getNodeType()!=Node.ELEMENT_NODE) {
                    continue;
                }

                if (((Element)currentChild).getTagName().equals("system-out")) {
                    if (seenStdout != null) {
                        unifyElements(seenStdout, currentChild, node);
                        i--;
                    } else {
                        seenStdout = currentChild;
                    }
                } else if (((Element)currentChild).getTagName().equals("system-err")) {
                    if (seenStderr != null) {
                        unifyElements(seenStderr, currentChild, node);
                        i--;
                    } else {
                        seenStderr = currentChild;
                    }
                }
            }

        } else {
            for (int i = 0; i < node.getChildNodes().getLength(); i++) {
                fixOutputXmlDoc(node.getChildNodes().item(i));
            }
        }
    }

    /**
     * Move all children of node to moveTarget, then remove node.
     * @param moveTarget target for children
     * @param node the node to remove
     * @param parent node's parent
     */
    private static void unifyElements(Node moveTarget, Node node, Node parent) {
        while (node.getChildNodes().getLength()>0) {
            Node toMove = node.getChildNodes().item(0);
            node.removeChild(toMove);
            moveTarget.appendChild(toMove);
        }
        parent.removeChild(node);
    }
}
