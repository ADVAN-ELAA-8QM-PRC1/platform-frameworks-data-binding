package com.android.databinding.annotationprocessor;

import android.binding.Bindable;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

@SupportedAnnotationTypes({"android.binding.Bindable"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ProcessBindable extends AbstractProcessor {

    private boolean mFileGenerated;

    public ProcessBindable() {
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mFileGenerated) {
            return false;
        }
        HashSet<String> properties = new HashSet<String>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Bindable.class)) {
//            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
//                    "Found Bindable: " + element);
            String name = getPropertyName(element);
            if (name != null) {
                properties.add(name);
            }
        }
        generateBR(properties);
        mFileGenerated = true;
        return true;
    }

    private void generateBR(HashSet<String> properties) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "************* Generating BR file from Bindable attributes");
        try {
            ArrayList<String> sortedProperties = new ArrayList<String>();
            sortedProperties.addAll(properties);
            Collections.sort(sortedProperties);

            JavaFileObject fileObject = processingEnv.getFiler()
                    .createSourceFile("android.binding.BR");
            Writer writer = fileObject.openWriter();
            writer.write("package android.binding;\n\n" +
                            "public final class BR {\n" +
                            "    public static final int _all = 0;\n"
            );
            int id = 0;
            for (String property : sortedProperties) {
                id++;
                writer.write("    public static final int " + property + " = " + id + ";\n");
            }
            writer.write("}\n");
            writer.close();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not generate BR file " + e.getLocalizedMessage());
        }
    }

    private String getPropertyName(Element element) {
        switch (element.getKind()) {
            case FIELD:
                return stripPrefixFromField((VariableElement) element);
            case METHOD:
                return stripPrefixFromMethod((ExecutableElement) element);
            default:
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Bindable is not allowed on " + element.getKind(), element);
                return null;
        }
    }

    private static String stripPrefixFromField(VariableElement element) {
        Name name = element.getSimpleName();
        if (name.length() >= 2) {
            char firstChar = name.charAt(0);
            char secondChar = name.charAt(1);
            if ((firstChar == 'm' && Character.isUpperCase(secondChar)) ||
                    (firstChar == '_' && Character.isJavaIdentifierStart(secondChar))) {
                return "" + Character.toLowerCase(secondChar) + name.subSequence(2, name.length());
            }
        }
        return name.toString();
    }

    private String stripPrefixFromMethod(ExecutableElement element) {
        Name name = element.getSimpleName();
        CharSequence propertyName;
        if (isGetter(element) || isSetter(element)) {
            propertyName = name.subSequence(3, name.length());
        } else if (isBooleanGetter(element)) {
            propertyName = name.subSequence(2, name.length());
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Bindable associated with method must follow JavaBeans convention", element);
            return null;
        }
        char firstChar = propertyName.charAt(0);
        return "" + Character.toLowerCase(firstChar) +
                propertyName.subSequence(1, propertyName.length());
    }

    private static boolean prefixes(CharSequence sequence, String prefix) {
        boolean prefixes = false;
        if (sequence.length() > prefix.length()) {
            int count = prefix.length();
            prefixes = true;
            for (int i = 0; i < count; i++) {
                if (sequence.charAt(i) != prefix.charAt(i)) {
                    prefixes = false;
                    break;
                }
            }
        }
        return prefixes;
    }

    private static boolean isGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "get") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() != TypeKind.VOID;
    }

    private static boolean isSetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "set") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().size() == 1 &&
                element.getReturnType().getKind() == TypeKind.VOID;
    }

    private static boolean isBooleanGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "is") &&
                Character.isJavaIdentifierStart(name.charAt(2)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() == TypeKind.BOOLEAN;
    }
}
