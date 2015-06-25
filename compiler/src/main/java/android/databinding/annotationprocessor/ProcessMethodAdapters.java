/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.databinding.annotationprocessor;

import android.databinding.BindingAdapter;
import android.databinding.BindingBuildInfo;
import android.databinding.BindingConversion;
import android.databinding.BindingMethod;
import android.databinding.BindingMethods;
import android.databinding.Untaggable;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.store.SetterStore;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.ReferenceType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

public class ProcessMethodAdapters extends ProcessDataBinding.ProcessingStep {
    public ProcessMethodAdapters() {
    }

    @Override
    public boolean onHandleStep(RoundEnvironment roundEnv,
            ProcessingEnvironment processingEnvironment, BindingBuildInfo buildInfo) {
        L.d("processing adapters");
        final ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
        Preconditions.checkNotNull(modelAnalyzer, "Model analyzer should be"
                + " initialized first");
        SetterStore store = SetterStore.get(modelAnalyzer);
        clearIncrementalClasses(roundEnv, store);

        addBindingAdapters(roundEnv, processingEnvironment, store);
        addRenamed(roundEnv, processingEnvironment, store);
        addConversions(roundEnv, processingEnvironment, store);
        addUntaggable(roundEnv, processingEnvironment, store);

        try {
            store.write(buildInfo.modulePackage(), processingEnvironment);
        } catch (IOException e) {
            L.e(e, "Could not write BindingAdapter intermediate file.");
        }
        return true;
    }

    @Override
    public void onProcessingOver(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment, BindingBuildInfo buildInfo) {

    }

    private void addBindingAdapters(RoundEnvironment roundEnv, ProcessingEnvironment
            processingEnv, SetterStore store) {
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, BindingAdapter.class)) {
            if (element.getKind() != ElementKind.METHOD ||
                    !element.getModifiers().contains(Modifier.STATIC) ||
                    !element.getModifiers().contains(Modifier.PUBLIC)) {
                L.e("@BindingAdapter on invalid element: %s", element);
                continue;
            }
            BindingAdapter bindingAdapter = element.getAnnotation(BindingAdapter.class);

            ExecutableElement executableElement = (ExecutableElement) element;
            List<? extends VariableElement> parameters = executableElement.getParameters();
            if (bindingAdapter.value().length == 0) {
                L.e("@BindingAdapter requires at least one attribute. %s", element);
                continue;
            }
            final int numAttributes = bindingAdapter.value().length;
            if (parameters.size() == 1 + (2 * numAttributes)) {
                // This BindingAdapter takes old and new values. Make sure they are properly ordered
                Types typeUtils = processingEnv.getTypeUtils();
                boolean hasParameterError = false;
                for (int i = 1; i <= numAttributes; i++) {
                    if (!typeUtils.isSameType(parameters.get(i).asType(),
                            parameters.get(i + numAttributes).asType())) {
                        L.e("BindingAdapter %s: old values should be followed by new values. " +
                                "Parameter %d must be the same type as parameter %d.",
                                executableElement, i + 1, i + numAttributes + 1);
                        hasParameterError = true;
                        break;
                    }
                }
                if (hasParameterError) {
                    continue;
                }
            } else if (parameters.size() != numAttributes + 1) {
                L.e("@BindingAdapter %s has %d attributes and %d parameters. There should be %d " +
                        "or %d parameters.", executableElement, numAttributes, parameters.size(),
                        numAttributes + 1, (numAttributes * 2) + 1);
                continue;
            }
            warnAttributeNamespaces(bindingAdapter.value());
            try {
                if (numAttributes == 1) {
                    final String attribute = bindingAdapter.value()[0];
                    L.d("------------------ @BindingAdapter for %s", element);
                    store.addBindingAdapter(processingEnv, attribute, executableElement);
                } else {
                    store.addBindingAdapter(processingEnv, bindingAdapter.value(),
                            executableElement);
                }
            } catch (IllegalArgumentException e) {
                L.e(e, "@BindingAdapter for duplicate View and parameter type: %s", element);
            }
        }
    }

    private static void warnAttributeNamespace(String attribute) {
        if (attribute.contains(":") && !attribute.startsWith("android:")) {
            L.w("Application namespace for attribute %s will be ignored.", attribute);
        }
    }

    private static void warnAttributeNamespaces(String[] attributes) {
        for (String attribute : attributes) {
            warnAttributeNamespace(attribute);
        }
    }

    private void addRenamed(RoundEnvironment roundEnv, ProcessingEnvironment processingEnv,
            SetterStore store) {
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, BindingMethods.class)) {
            BindingMethods bindingMethods = element.getAnnotation(BindingMethods.class);

            for (BindingMethod bindingMethod : bindingMethods.value()) {
                final String attribute = bindingMethod.attribute();
                final String method = bindingMethod.method();
                warnAttributeNamespace(attribute);
                String type;
                try {
                    type = bindingMethod.type().getCanonicalName();
                } catch (MirroredTypeException e) {
                    type = e.getTypeMirror().toString();
                }
                store.addRenamedMethod(attribute, type, method, (TypeElement) element);
            }
        }
    }

    private void addConversions(RoundEnvironment roundEnv,
            ProcessingEnvironment processingEnv, SetterStore store) {
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, BindingConversion.class)) {
            if (element.getKind() != ElementKind.METHOD ||
                    !element.getModifiers().contains(Modifier.STATIC) ||
                    !element.getModifiers().contains(Modifier.PUBLIC)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingConversion is only allowed on public static methods: " + element);
                continue;
            }

            ExecutableElement executableElement = (ExecutableElement) element;
            if (executableElement.getParameters().size() != 1) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingConversion method should have one parameter: " + element);
                continue;
            }
            if (executableElement.getReturnType().getKind() == TypeKind.VOID) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingConversion method must return a value: " + element);
                continue;
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "added conversion: " + element);
            store.addConversionMethod(executableElement);
        }
    }

    private void addUntaggable(RoundEnvironment roundEnv,
            ProcessingEnvironment processingEnv, SetterStore store) {
        for (Element element : AnnotationUtil.getElementsAnnotatedWith(roundEnv, Untaggable.class)) {
            Untaggable untaggable = element.getAnnotation(Untaggable.class);
            store.addUntaggableTypes(untaggable.value(), (TypeElement) element);
        }
    }

    private void clearIncrementalClasses(RoundEnvironment roundEnv, SetterStore store) {
        HashSet<String> classes = new HashSet<String>();

        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, BindingAdapter.class)) {
            TypeElement containingClass = (TypeElement) element.getEnclosingElement();
            classes.add(containingClass.getQualifiedName().toString());
        }
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, BindingMethods.class)) {
            classes.add(((TypeElement) element).getQualifiedName().toString());
        }
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, BindingConversion.class)) {
            classes.add(((TypeElement) element.getEnclosingElement()).getQualifiedName().
                    toString());
        }
        for (Element element : AnnotationUtil.getElementsAnnotatedWith(roundEnv, Untaggable.class)) {
            classes.add(((TypeElement) element).getQualifiedName().toString());
        }
        store.clear(classes);
    }

}
