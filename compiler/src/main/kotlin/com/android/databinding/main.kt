/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding

import org.w3c.dom.Document
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.SAXException

import java.io.File
import java.io.FilenameFilter
import java.io.IOException
import java.util.ArrayList

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory
import kotlin.properties.Delegates
import java.util.HashMap

import javax.xml.namespace.NamespaceContext
import com.android.databinding.ext.forEach
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.dom.DOMSource
import java.io.FileOutputStream
import com.android.databinding.ext.toArrayList
import com.android.databinding.util.XmlEditor
import com.android.databinding.util.Log
import com.android.databinding.LayoutBinder
import com.android.databinding.DataBinder
import com.android.databinding.writer.DataBinderWriter
import com.android.databinding.ClassAnalyzer


public class KLayoutParser(val appPkg : String, val resourceFolders : kotlin.Iterable<File>,
        val outputBaseDir : File, val outputResBaseDir : File) {
    var dbr : DataBinderWriter by Delegates.notNull()
    var processed = false
    public var classAnalyzer : ClassAnalyzer by Delegates.notNull()

    val jDataBinder = DataBinder();

    val outputResDir by Delegates.lazy { File(outputResBaseDir, "values") }

    val outputDir by Delegates.lazy {
        File(outputBaseDir.getAbsolutePath() + "/" + appPkg.replace('.','/') + "/generated")
    }

    val dbrOutputDir by Delegates.lazy {
        File(outputBaseDir.getAbsolutePath() + "/" + dbr.pkg.replace('.','/'))
    }

    class object {
        val XPATH_VARIABLE_DEFINITIONS = "//variable"
        val XPATH_IMPORT_DEFINITIONS = "//import"
        //val XPATH_BINDING_EXPR = "//@*[starts-with(name(), 'bind')]"
        val XPATH_STATIC_BINDING_EXPR = "//@*[starts-with(name(), 'bind')]"
        val XPATH_BINDING_2_EXPR = "//@*[starts-with(., '{') and substring(., string-length(.)) = '}']"
        val XPATH_BINDING_ELEMENTS = "$XPATH_BINDING_2_EXPR/.."
    }

    fun log (s : String) = System.out.println("LOG:$s");

    public fun processIfNecessary() {
        if (!processed) {
            processed = true
            process()
        }
    }

    fun process() {
        val xmlFiles = ArrayList<File>()
        resourceFolders.filter({it.exists()}).forEach {
            findLayoutFolders(it).forEach {
                findXmlFiles(it, xmlFiles)
            }
        }
        //viewBinderRenderers.clear()
        for (xml in xmlFiles) {
            log("xmlFile $xml")
            val layoutBinder = parseAndStripXml(xml)
            if (layoutBinder == null) {
                log("no bindings in $xml, skipping")
                continue
            }
            layoutBinder.setProjectPackage("$appPkg");
            layoutBinder.setPackage("$appPkg.generated")
            layoutBinder.setBaseClassName("${toClassName(xml.name)}Binder")
            layoutBinder.setLayoutname(toLayoutId(xml.name))

        }
        dbr = DataBinderWriter("com.android.databinding.library", appPkg,
                "GeneratedDataBinderRenderer", jDataBinder.getLayoutBinders())
    }

    public fun writeAttrFile() {
        outputResDir.mkdirs()
//        writeToFile(File(outputResDir, "bindingattrs.xml"), styler.render())
    }

    public fun writeDbrFile() : Unit = writeDbrFile(dbrOutputDir)
    public fun writeDbrFile(dir : File) : Unit {
        dir.mkdirs()
        writeToFile(File(dir, "${dbr.className}.java"), dbr.write())
    }

    public fun writeViewBinderInterfaces() : Unit = writeViewBinderInterfaces(outputDir)

    public fun writeViewBinderInterfaces(dir : File) : Unit {
        dir.mkdirs()
        jDataBinder.getLayoutBinders().forEach {
            writeToFile(File(dir, "${it.getInterfaceName()}.java"), it.writeViewBinderInterface())
        }
    }

    public fun writeViewBinders() : Unit = writeViewBinders(outputDir)

    public fun writeViewBinders(dir : File) : Unit {
        dir.mkdirs()
        jDataBinder.getLayoutBinders().forEach {
            writeToFile(File(dir, "${it.getClassName()}.java"), it.writeViewBinder())
        }

    }

    private fun writeToFile(file : File, contents : String) {
        System.out.println("output file: ${file.getAbsolutePath()}")
        file.writeText(contents)
    }

    private fun toClassName(name:String) : String =
        name.substring(0, name.indexOf(".")).split("_").map { "${it.substring(0,1).toUpperCase()}${it.substring(1)}" }.join("")

    private fun toLayoutId(name:String) : String =
            name.substring(0, name.indexOf("."))

    private fun stripBindingTags(xml  : File) {
        val res = XmlEditor.strip(xml)
        if (res != null) {
            Log.d{"file ${xml.getName()} has changed, overwriting ${xml.getAbsolutePath()}"}
            xml.writeText(res)
        }
    }

    private fun stripFileAndGetOriginal(xml : File) : File? {
        System.out.println("parsing resourceY file ${xml.getAbsolutePath()}")
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xml)
        val xPathFactory = XPathFactory.newInstance()
        val xPath = xPathFactory.newXPath()
        val commentElementExpr = xPath.compile("//comment()[starts-with(., \" From: file:\")][last()]")
        var commentElementNodes = commentElementExpr.evaluate(doc, XPathConstants.NODESET) as NodeList
        System.out.println("comment element nodes count ${commentElementNodes.getLength()}")
        if (commentElementNodes.getLength() == 0) {
            System.out.println("cannot find comment element to find the actual file")
            return null
        }
        val first = commentElementNodes.item(0)
        val actualFilePath = first.getNodeValue().substring(" From: file:".length()).trim()
        System.out.println("actual file to parse: ${actualFilePath}")
        val actualFile = File(actualFilePath)
        if (!actualFile.canRead()) {
            System.out.println("cannot find original, skipping. ${actualFile.getAbsolutePath()}")
            return null
        }

        // now if file has any binding expressions, find and delete them
        val variableNodes = getVariableNodes(doc, xPath)
        var changed = variableNodes.getLength() > 0 //TODO do we need to check more?
        if (changed) {
            stripBindingTags(xml)
        }

        return actualFile
    }

    private fun parseAndStripXml(xml : File) : LayoutBinder? {
        val original = stripFileAndGetOriginal(xml)
        return if (original == null) {
            null
        } else {
            jDataBinder.parseXml(original)
        }
    }

    private fun getBindingNodes(doc: Document, xPath: XPath): NodeList {
        val expr = xPath.compile(XPATH_BINDING_ELEMENTS)
        return expr.evaluate(doc, XPathConstants.NODESET) as NodeList
    }

    private fun getVariableNodes(doc: Document?, xPath: XPath): NodeList {
        val expr = xPath.compile(XPATH_VARIABLE_DEFINITIONS)
        return expr.evaluate(doc, XPathConstants.NODESET) as NodeList
    }

    private fun getImportNodes(doc: Document?, xPath: XPath): NodeList {
        val expr = xPath.compile(XPATH_IMPORT_DEFINITIONS)
        return expr.evaluate(doc, XPathConstants.NODESET) as NodeList
    }

    private fun findLayoutFolders(resources: File): Array<File> {
        val filenameFilter = object : FilenameFilter {
            override fun accept(dir: File, name: String): Boolean {
                return name.startsWith("layout")
            }
        }
        return resources.listFiles(filenameFilter)
    }

    var xmlFilter: FilenameFilter = object : FilenameFilter {
        override fun accept(dir: File, name: String): Boolean {
            return name.toLowerCase().endsWith(".xml")
        }
    }

    private fun findXmlFiles(root: File, out: MutableList<File>) {
        if (!root.exists()) {
            return
        }
        if (root.isDirectory()) {
            for (file in root.listFiles(xmlFilter)) {
                if ("." == file.getName() || ".." == file.getName()) {
                    continue
                }
                if (file.isDirectory()) {
                    findXmlFiles(file, out)
                } else if (xmlFilter.accept(file, file.getName())) {
                    out.add(file)
                }
            }
        }
    }

    fun getFullViewClassName(viewName: String): String {
        if (viewName.indexOf('.') == -1) {
            if (viewName == "View"  || viewName == "ViewGroup") {
                return "android.view.$viewName"
            }
            return "android.widget.$viewName"
        }
        return viewName
    }
}
