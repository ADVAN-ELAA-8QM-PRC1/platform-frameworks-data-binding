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
package android.databinding.tool.store;

import com.google.common.base.Preconditions;

import org.apache.commons.lang3.StringUtils;

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.util.GenerationalClassUtil;
import android.databinding.tool.util.L;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public class SetterStore {

    public static final String SETTER_STORE_FILE_EXT = "-setter_store.bin";

    private static SetterStore sStore;

    private final IntermediateV1 mStore;
    private final ModelAnalyzer mClassAnalyzer;

    private Comparator<MultiAttributeSetter> COMPARE_MULTI_ATTRIBUTE_SETTERS =
            new Comparator<MultiAttributeSetter>() {
                @Override
                public int compare(MultiAttributeSetter o1, MultiAttributeSetter o2) {
                    if (o1.attributes.length != o2.attributes.length) {
                        return o2.attributes.length - o1.attributes.length;
                    }
                    ModelClass view1 = mClassAnalyzer.findClass(o1.mKey.viewType, null);
                    ModelClass view2 = mClassAnalyzer.findClass(o2.mKey.viewType, null);
                    if (!view1.equals(view2)) {
                        if (view1.isAssignableFrom(view2)) {
                            return 1;
                        } else {
                            return -1;
                        }
                    }
                    if (!o1.mKey.attributeIndices.keySet()
                            .equals(o2.mKey.attributeIndices.keySet())) {
                        // order by attribute name
                        Iterator<String> o1Keys = o1.mKey.attributeIndices.keySet().iterator();
                        Iterator<String> o2Keys = o2.mKey.attributeIndices.keySet().iterator();
                        while (o1Keys.hasNext()) {
                            String key1 = o1Keys.next();
                            String key2 = o2Keys.next();
                            int compare = key1.compareTo(key2);
                            if (compare != 0) {
                                return compare;
                            }
                        }
                        Preconditions.checkState(false,
                                "The sets don't match! That means the keys shouldn't match also");
                    }
                    // Same view type. Same attributes
                    for (String attribute : o1.mKey.attributeIndices.keySet()) {
                        final int index1 = o1.mKey.attributeIndices.get(attribute);
                        final int index2 = o2.mKey.attributeIndices.get(attribute);
                        ModelClass type1 = mClassAnalyzer
                                .findClass(o1.mKey.parameterTypes[index1], null);
                        ModelClass type2 = mClassAnalyzer
                                .findClass(o2.mKey.parameterTypes[index2], null);
                        if (type1.equals(type2)) {
                            continue;
                        }
                        if (o1.mCasts[index1] != null) {
                            if (o2.mCasts[index2] == null) {
                                return 1; // o2 is better
                            } else {
                                continue; // both are casts
                            }
                        } else if (o2.mCasts[index2] != null) {
                            return -1; // o1 is better
                        }
                        if (o1.mConverters[index1] != null) {
                            if (o2.mConverters[index2] == null) {
                                return 1; // o2 is better
                            } else {
                                continue; // both are conversions
                            }
                        } else if (o2.mConverters[index2] != null) {
                            return -1; // o1 is better
                        }

                        if (type1.isPrimitive()) {
                            if (type2.isPrimitive()) {
                                int type1ConversionLevel = ModelMethod
                                        .getImplicitConversionLevel(type1);
                                int type2ConversionLevel = ModelMethod
                                        .getImplicitConversionLevel(type2);
                                return type2ConversionLevel - type1ConversionLevel;
                            } else {
                                // type1 is primitive and has higher priority
                                return -1;
                            }
                        } else if (type2.isPrimitive()) {
                            return 1;
                        }
                        if (type1.isAssignableFrom(type2)) {
                            return 1;
                        } else if (type2.isAssignableFrom(type1)) {
                            return -1;
                        }
                    }
                    // hmmm... same view type, same attributes, same parameter types... ?
                    return 0;
                }
            };

    private SetterStore(ModelAnalyzer modelAnalyzer, IntermediateV1 store) {
        mClassAnalyzer = modelAnalyzer;
        mStore = store;
    }

    public static SetterStore get(ModelAnalyzer modelAnalyzer) {
        if (sStore == null) {
            sStore = load(modelAnalyzer, SetterStore.class.getClassLoader());
        }
        return sStore;
    }

    private static SetterStore load(ModelAnalyzer modelAnalyzer, ClassLoader classLoader) {
        IntermediateV1 store = new IntermediateV1();
        List<Intermediate> previousStores = GenerationalClassUtil
                .loadObjects(classLoader,
                        new GenerationalClassUtil.ExtensionFilter(SETTER_STORE_FILE_EXT));
        for (Intermediate intermediate : previousStores) {
            merge(store, intermediate);
        }
        return new SetterStore(modelAnalyzer, store);
    }

    public void addRenamedMethod(String attribute, String declaringClass, String method,
            TypeElement declaredOn) {
        attribute = stripNamespace(attribute);
        HashMap<String, MethodDescription> renamed = mStore.renamedMethods.get(attribute);
        if (renamed == null) {
            renamed = new HashMap<String, MethodDescription>();
            mStore.renamedMethods.put(attribute, renamed);
        }
        MethodDescription methodDescription =
                new MethodDescription(declaredOn.getQualifiedName().toString(), method);
        L.d("STORE addmethod desc %s", methodDescription);
        renamed.put(declaringClass, methodDescription);
    }

    public void addBindingAdapter(String attribute, ExecutableElement bindingMethod) {
        attribute = stripNamespace(attribute);
        L.d("STORE addBindingAdapter %s %s", attribute, bindingMethod);
        HashMap<AccessorKey, MethodDescription> adapters = mStore.adapterMethods.get(attribute);

        if (adapters == null) {
            adapters = new HashMap<AccessorKey, MethodDescription>();
            mStore.adapterMethods.put(attribute, adapters);
        }
        List<? extends VariableElement> parameters = bindingMethod.getParameters();
        String view = getQualifiedName(parameters.get(0).asType());
        String value = getQualifiedName(parameters.get(1).asType());

        AccessorKey key = new AccessorKey(view, value);
        if (adapters.containsKey(key)) {
            throw new IllegalArgumentException("Already exists!");
        }

        adapters.put(key, new MethodDescription(bindingMethod));
    }

    public void addBindingAdapter(String[] attributes, ExecutableElement bindingMethod) {
        L.d("STORE add multi-value BindingAdapter %d %s", attributes.length, bindingMethod);
        MultiValueAdapterKey key = new MultiValueAdapterKey(bindingMethod, attributes);
        MethodDescription methodDescription = new MethodDescription(bindingMethod);
        mStore.multiValueAdapters.put(key, methodDescription);
    }

    private static String[] stripAttributes(String[] attributes) {
        String[] strippedAttributes = new String[attributes.length];
        for (int i = 0; i < attributes.length; i++) {
            strippedAttributes[i] = stripNamespace(attributes[i]);
        }
        return strippedAttributes;
    }

    public void addUntaggableTypes(String[] typeNames, TypeElement declaredOn) {
        L.d("STORE addUntaggableTypes %s %s", Arrays.toString(typeNames), declaredOn);
        String declaredType = declaredOn.getQualifiedName().toString();
        for (String type : typeNames) {
            mStore.untaggableTypes.put(type, declaredType);
        }
    }

    private static String getQualifiedName(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
            case VOID:
                return type.toString();
            case ARRAY:
                return getQualifiedName(((ArrayType) type).getComponentType()) + "[]";
            case DECLARED:
                return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName()
                        .toString();
            default:
                return "-- no type --";
        }
    }

    public void addConversionMethod(ExecutableElement conversionMethod) {
        L.d("STORE addConversionMethod %s", conversionMethod);
        List<? extends VariableElement> parameters = conversionMethod.getParameters();
        String fromType = getQualifiedName(parameters.get(0).asType());
        String toType = getQualifiedName(conversionMethod.getReturnType());
        MethodDescription methodDescription = new MethodDescription(conversionMethod);
        HashMap<String, MethodDescription> convertTo = mStore.conversionMethods.get(fromType);
        if (convertTo == null) {
            convertTo = new HashMap<String, MethodDescription>();
            mStore.conversionMethods.put(fromType, convertTo);
        }
        convertTo.put(toType, methodDescription);
    }

    public void clear(Set<String> classes) {
        ArrayList<AccessorKey> removedAccessorKeys = new ArrayList<AccessorKey>();
        for (HashMap<AccessorKey, MethodDescription> adapters : mStore.adapterMethods.values()) {
            for (AccessorKey key : adapters.keySet()) {
                MethodDescription description = adapters.get(key);
                if (classes.contains(description.type)) {
                    removedAccessorKeys.add(key);
                }
            }
            removeFromMap(adapters, removedAccessorKeys);
        }

        ArrayList<String> removedRenamed = new ArrayList<String>();
        for (HashMap<String, MethodDescription> renamed : mStore.renamedMethods.values()) {
            for (String key : renamed.keySet()) {
                if (classes.contains(renamed.get(key).type)) {
                    removedRenamed.add(key);
                }
            }
            removeFromMap(renamed, removedRenamed);
        }

        ArrayList<String> removedConversions = new ArrayList<String>();
        for (HashMap<String, MethodDescription> convertTos : mStore.conversionMethods.values()) {
            for (String toType : convertTos.keySet()) {
                MethodDescription methodDescription = convertTos.get(toType);
                if (classes.contains(methodDescription.type)) {
                    removedConversions.add(toType);
                }
            }
            removeFromMap(convertTos, removedConversions);
        }

        ArrayList<String> removedUntaggable = new ArrayList<String>();
        for (String typeName : mStore.untaggableTypes.keySet()) {
            if (classes.contains(mStore.untaggableTypes.get(typeName))) {
                removedUntaggable.add(typeName);
            }
        }
        removeFromMap(mStore.untaggableTypes, removedUntaggable);
    }

    private static <K, V> void removeFromMap(Map<K, V> map, List<K> keys) {
        for (K key : keys) {
            map.remove(key);
        }
        keys.clear();
    }

    public void write(String projectPackage, ProcessingEnvironment processingEnvironment)
            throws IOException {
        GenerationalClassUtil.writeIntermediateFile(processingEnvironment,
                projectPackage, projectPackage + SETTER_STORE_FILE_EXT, mStore);
    }

    private static String stripNamespace(String attribute) {
        if (!attribute.startsWith("android:")) {
            int colon = attribute.indexOf(':');
            if (colon >= 0) {
                attribute = attribute.substring(colon + 1);
            }
        }
        return attribute;
    }

    public List<MultiAttributeSetter> getMultiAttributeSetterCalls(String[] attributes,
            ModelClass viewType, ModelClass[] valueType) {
        attributes = stripAttributes(attributes);
        final ArrayList<MultiAttributeSetter> calls = new ArrayList<MultiAttributeSetter>();
        ArrayList<MultiAttributeSetter> matching = getMatchingMultiAttributeSetters(attributes,
                viewType, valueType);
        Collections.sort(matching, COMPARE_MULTI_ATTRIBUTE_SETTERS);
        while (!matching.isEmpty()) {
            MultiAttributeSetter bestMatch = matching.get(0);
            calls.add(bestMatch);
            removeConsumedAttributes(matching, bestMatch.attributes);
        }
        return calls;
    }

    // Removes all MultiAttributeSetters that require any of the values in attributes
    private static void removeConsumedAttributes(ArrayList<MultiAttributeSetter> matching,
            String[] attributes) {
        for (int i = matching.size() - 1; i >= 0; i--) {
            final MultiAttributeSetter setter = matching.get(i);
            boolean found = false;
            for (String attribute : attributes) {
                if (isInArray(attribute, setter.attributes)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                matching.remove(i);
            }
        }
    }

    // Linear search through the String array for a specific value.
    private static boolean isInArray(String str, String[] array) {
        for (String value : array) {
            if (value.equals(str)) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<MultiAttributeSetter> getMatchingMultiAttributeSetters(String[] attributes,
            ModelClass viewType, ModelClass[] valueType) {
        final ArrayList<MultiAttributeSetter> setters = new ArrayList<MultiAttributeSetter>();
        for (MultiValueAdapterKey adapter : mStore.multiValueAdapters.keySet()) {
            if (adapter.attributes.length > attributes.length) {
                continue;
            }
            final ModelClass viewClass = mClassAnalyzer.findClass(adapter.viewType, null);
            if (!viewClass.isAssignableFrom(viewType)) {
                continue;
            }
            final MethodDescription method = mStore.multiValueAdapters.get(adapter);
            final MultiAttributeSetter setter = createMultiAttributeSetter(method, attributes,
                    valueType, adapter);
            if (setter != null) {
                setters.add(setter);
            }
        }
        return setters;
    }

    private MultiAttributeSetter createMultiAttributeSetter(MethodDescription method,
            String[] allAttributes, ModelClass[] attributeValues, MultiValueAdapterKey adapter) {
        int matchingAttributes = 0;
        String[] casts = new String[adapter.attributes.length];
        MethodDescription[] conversions = new MethodDescription[adapter.attributes.length];

        for (int i = 0; i < allAttributes.length; i++) {
            Integer index = adapter.attributeIndices.get(allAttributes[i]);
            if (index != null) {
                matchingAttributes++;
                final String parameterTypeStr = adapter.parameterTypes[index];
                final ModelClass parameterType = mClassAnalyzer.findClass(parameterTypeStr, null);
                final ModelClass attributeType = attributeValues[i];
                if (!parameterType.isAssignableFrom(attributeType)) {
                    if (ModelMethod.isBoxingConversion(parameterType, attributeType)) {
                        // automatic boxing is ok
                        continue;
                    } else if (ModelMethod.isImplicitConversion(attributeType, parameterType)) {
                        // implicit conversion is ok
                        continue;
                    }
                    // Look for a converter
                    conversions[index] = getConversionMethod(attributeType, parameterType, null);
                    if (conversions[index] == null) {
                        if (attributeType.isObject()) {
                            // Cast is allowed also
                            casts[index] = parameterTypeStr;
                        } else {
                            // Parameter type mismatch
                            return null;
                        }
                    }
                }
            }
        }

        if (matchingAttributes != adapter.attributes.length) {
            return null;
        } else {
            return new MultiAttributeSetter(adapter, adapter.attributes, method, conversions,
                    casts);
        }
    }

    public SetterCall getSetterCall(String attribute, ModelClass viewType,
            ModelClass valueType, Map<String, String> imports) {
        attribute = stripNamespace(attribute);
        SetterCall setterCall = null;
        MethodDescription conversionMethod = null;
        if (viewType != null) {
            HashMap<AccessorKey, MethodDescription> adapters = mStore.adapterMethods.get(attribute);
            ModelMethod bestSetterMethod = getBestSetter(viewType, valueType, attribute, imports);
            ModelClass bestViewType = null;
            ModelClass bestValueType = null;
            if (bestSetterMethod != null) {
                bestViewType = bestSetterMethod.getDeclaringClass();
                bestValueType = bestSetterMethod.getParameterTypes()[0];
                setterCall = new ModelMethodSetter(bestSetterMethod);
            }

            if (adapters != null) {
                for (AccessorKey key : adapters.keySet()) {
                    try {
                        ModelClass adapterViewType = mClassAnalyzer
                                .findClass(key.viewType, imports);
                        if (adapterViewType != null && adapterViewType.isAssignableFrom(viewType)) {
                            try {
                                ModelClass adapterValueType = mClassAnalyzer
                                        .findClass(key.valueType, imports);
                                boolean isBetterView = bestViewType == null ||
                                        bestValueType.isAssignableFrom(adapterValueType);
                                if (isBetterParameter(valueType, adapterValueType, bestValueType,
                                        isBetterView, imports)) {
                                    bestViewType = adapterViewType;
                                    bestValueType = adapterValueType;
                                    MethodDescription adapter = adapters.get(key);
                                    setterCall = new AdapterSetter(adapter);
                                }

                            } catch (Exception e) {
                                L.e(e, "Unknown class: %s", key.valueType);
                            }
                        }
                    } catch (Exception e) {
                        L.e(e, "Unknown class: %s", key.viewType);
                    }
                }
            }

            conversionMethod = getConversionMethod(valueType, bestValueType, imports);
            if (valueType.isObject() && setterCall != null && bestValueType.isNullable()) {
                setterCall.setCast(bestValueType);
            }
        }
        if (setterCall == null) {
            if (viewType != null && !viewType.isViewDataBinding()) {
                L.e("Cannot find the setter for attribute '%s' on %s.", attribute,
                        viewType.getCanonicalName());
            }
            setterCall = new DummySetter(getDefaultSetter(attribute));
        }
        setterCall.setConverter(conversionMethod);
        return setterCall;
    }

    public boolean isUntaggable(String viewType) {
        return mStore.untaggableTypes.containsKey(viewType);
    }

    private ModelMethod getBestSetter(ModelClass viewType, ModelClass argumentType,
            String attribute, Map<String, String> imports) {
        if (viewType.isGeneric()) {
            viewType = viewType.erasure();
            argumentType = argumentType.erasure();
        }
        List<String> setterCandidates = new ArrayList<String>();
        HashMap<String, MethodDescription> renamed = mStore.renamedMethods.get(attribute);
        if (renamed != null) {
            for (String className : renamed.keySet()) {
                try {
                    ModelClass renamedViewType = mClassAnalyzer.findClass(className, imports);
                    if (renamedViewType.isAssignableFrom(viewType)) {
                        setterCandidates.add(renamed.get(className).method);
                        break;
                    }
                } catch (Exception e) {
                    //printMessage(Diagnostic.Kind.NOTE, "Unknown class: " + className);
                }
            }
        }
        setterCandidates.add(getDefaultSetter(attribute));
        setterCandidates.add(trimAttributeNamespace(attribute));

        ModelMethod bestMethod = null;
        ModelClass bestParameterType = null;
        List<ModelClass> args = new ArrayList<ModelClass>();
        args.add(argumentType);
        for (String name : setterCandidates) {
            ModelMethod[] methods = viewType.getMethods(name, 1);

            for (ModelMethod method : methods) {
                ModelClass[] parameterTypes = method.getParameterTypes();
                ModelClass param = parameterTypes[0];
                if (method.isVoid() &&
                        isBetterParameter(argumentType, param, bestParameterType, true, imports)) {
                    bestParameterType = param;
                    bestMethod = method;
                }
            }
        }
        return bestMethod;

    }

    private static String trimAttributeNamespace(String attribute) {
        final int colonIndex = attribute.indexOf(':');
        return colonIndex == -1 ? attribute : attribute.substring(colonIndex + 1);
    }

    private static String getDefaultSetter(String attribute) {
        return "set" + StringUtils.capitalize(trimAttributeNamespace(attribute));
    }

    private boolean isBetterParameter(ModelClass argument, ModelClass parameter,
            ModelClass oldParameter, boolean isBetterViewTypeMatch, Map<String, String> imports) {
        // Right view type. Check the value
        if (!isBetterViewTypeMatch && oldParameter.equals(argument)) {
            return false;
        } else if (argument.equals(parameter)) {
            // Exact match
            return true;
        } else if (!isBetterViewTypeMatch &&
                ModelMethod.isBoxingConversion(oldParameter, argument)) {
            return false;
        } else if (ModelMethod.isBoxingConversion(parameter, argument)) {
            // Boxing/unboxing is second best
            return true;
        } else {
            int oldConversionLevel = ModelMethod.getImplicitConversionLevel(oldParameter);
            if (ModelMethod.isImplicitConversion(argument, parameter)) {
                // Better implicit conversion
                int conversionLevel = ModelMethod.getImplicitConversionLevel(parameter);
                return oldConversionLevel < 0 || conversionLevel < oldConversionLevel;
            } else if (oldConversionLevel >= 0) {
                return false;
            } else if (parameter.isAssignableFrom(argument)) {
                // Right type, see if it is better than the current best match.
                if (oldParameter == null) {
                    return true;
                } else {
                    return oldParameter.isAssignableFrom(parameter);
                }
            } else {
                MethodDescription conversionMethod = getConversionMethod(argument, parameter,
                        imports);
                if (conversionMethod != null) {
                    return true;
                }
                if (getConversionMethod(argument, oldParameter, imports) != null) {
                    return false;
                }
                return argument.isObject() && !parameter.isPrimitive();
            }
        }
    }

    private MethodDescription getConversionMethod(ModelClass from, ModelClass to,
            Map<String, String> imports) {
        if (from != null && to != null) {
            for (String fromClassName : mStore.conversionMethods.keySet()) {
                try {
                    ModelClass convertFrom = mClassAnalyzer.findClass(fromClassName, imports);
                    if (canUseForConversion(from, convertFrom)) {
                        HashMap<String, MethodDescription> conversion =
                                mStore.conversionMethods.get(fromClassName);
                        for (String toClassName : conversion.keySet()) {
                            try {
                                ModelClass convertTo = mClassAnalyzer.findClass(toClassName,
                                        imports);
                                if (canUseForConversion(convertTo, to)) {
                                    return conversion.get(toClassName);
                                }
                            } catch (Exception e) {
                                L.d(e, "Unknown class: %s", toClassName);
                            }
                        }
                    }
                } catch (Exception e) {
                    L.d(e, "Unknown class: %s", fromClassName);
                }
            }
        }
        return null;
    }

    private boolean canUseForConversion(ModelClass from, ModelClass to) {
        return from.equals(to) || ModelMethod.isBoxingConversion(from, to) ||
                to.isAssignableFrom(from);
    }

    private static void merge(IntermediateV1 store, Intermediate dumpStore) {
        IntermediateV1 intermediateV1 = (IntermediateV1) dumpStore.upgrade();
        merge(store.adapterMethods, intermediateV1.adapterMethods);
        merge(store.renamedMethods, intermediateV1.renamedMethods);
        merge(store.conversionMethods, intermediateV1.conversionMethods);
        store.multiValueAdapters.putAll(intermediateV1.multiValueAdapters);
        store.untaggableTypes.putAll(intermediateV1.untaggableTypes);
    }

    private static <K, V> void merge(HashMap<K, HashMap<V, MethodDescription>> first,
            HashMap<K, HashMap<V, MethodDescription>> second) {
        for (K key : second.keySet()) {
            HashMap<V, MethodDescription> firstVals = first.get(key);
            HashMap<V, MethodDescription> secondVals = second.get(key);
            if (firstVals == null) {
                first.put(key, secondVals);
            } else {
                for (V key2 : secondVals.keySet()) {
                    if (!firstVals.containsKey(key2)) {
                        firstVals.put(key2, secondVals.get(key2));
                    }
                }
            }
        }
    }

    private static class MultiValueAdapterKey implements Serializable {
        private static final long serialVersionUID = 1;

        public final String viewType;

        public final String[] attributes;

        public final String[] parameterTypes;

        public final TreeMap<String, Integer> attributeIndices = new TreeMap<String, Integer>();

        public MultiValueAdapterKey(ExecutableElement method, String[] attributes) {
            this.attributes = stripAttributes(attributes);
            List<? extends VariableElement> parameters = method.getParameters();
            this.viewType = getQualifiedName(parameters.get(0).asType());
            this.parameterTypes = new String[parameters.size() - 1];
            for (int i = 0; i < parameterTypes.length; i++) {
                this.parameterTypes[i] = getQualifiedName(parameters.get(i + 1).asType());
                attributeIndices.put(this.attributes[i], i);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MultiValueAdapterKey)) {
                return false;
            }
            final MultiValueAdapterKey that = (MultiValueAdapterKey) obj;
            if (!this.viewType.equals(that.viewType) ||
                    this.attributes.length != that.attributes.length ||
                    !this.attributeIndices.keySet().equals(that.attributeIndices.keySet())) {
                return false;
            }

            for (int i = 0; i < this.attributes.length; i++) {
                final int thatIndex = that.attributeIndices.get(this.attributes[i]);
                final String thisParameter = parameterTypes[i];
                final String thatParameter = that.parameterTypes[thatIndex];
                if (!thisParameter.equals(thatParameter)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(viewType, attributeIndices.keySet());
        }
    }

    private static class MethodDescription implements Serializable {

        private static final long serialVersionUID = 1;

        public final String type;

        public final String method;

        public MethodDescription(String type, String method) {
            this.type = type;
            this.method = method;
            L.d("BINARY created method desc 1 %s %s", type, method );
        }

        public MethodDescription(ExecutableElement method) {
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();
            this.type = enclosingClass.getQualifiedName().toString();
            this.method = method.getSimpleName().toString();
            L.d("BINARY created method desc 2 %s %s, %s", type, this.method, method);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MethodDescription) {
                MethodDescription that = (MethodDescription) obj;
                return that.type.equals(this.type) && that.method.equals(this.method);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, method);
        }

        @Override
        public String toString() {
            return type + "." + method + "()";
        }
    }

    private static class AccessorKey implements Serializable {

        private static final long serialVersionUID = 1;

        public final String viewType;

        public final String valueType;

        public AccessorKey(String viewType, String valueType) {
            this.viewType = viewType;
            this.valueType = valueType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(viewType, valueType);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof AccessorKey) {
                AccessorKey that = (AccessorKey) obj;
                return viewType.equals(that.valueType) && valueType.equals(that.valueType);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return "AK(" + viewType + ", " + valueType + ")";
        }
    }

    private interface Intermediate extends Serializable {
        Intermediate upgrade();
    }

    private static class IntermediateV1 implements Serializable, Intermediate {
        private static final long serialVersionUID = 1;
        public final HashMap<String, HashMap<AccessorKey, MethodDescription>> adapterMethods =
                new HashMap<String, HashMap<AccessorKey, MethodDescription>>();
        public final HashMap<String, HashMap<String, MethodDescription>> renamedMethods =
                new HashMap<String, HashMap<String, MethodDescription>>();
        public final HashMap<String, HashMap<String, MethodDescription>> conversionMethods =
                new HashMap<String, HashMap<String, MethodDescription>>();
        public final HashMap<String, String> untaggableTypes = new HashMap<String, String>();
        public final HashMap<MultiValueAdapterKey, MethodDescription> multiValueAdapters =
                new HashMap<MultiValueAdapterKey, MethodDescription>();

        public IntermediateV1() {
        }

        @Override
        public Intermediate upgrade() {
            return this;
        }
    }

    public static class DummySetter extends SetterCall {
        private String mMethodName;

        public DummySetter(String methodName) {
            mMethodName = methodName;
        }

        @Override
        public String toJavaInternal(String viewExpression, String valueExpression) {
            return viewExpression + "." + mMethodName + "(" + valueExpression + ")";
        }

        @Override
        public int getMinApi() {
            return 1;
        }
    }

    public static class AdapterSetter extends SetterCall {
        final MethodDescription mAdapter;

        public AdapterSetter(MethodDescription adapter) {
            mAdapter = adapter;
        }

        @Override
        public String toJavaInternal(String viewExpression, String valueExpression) {
            return mAdapter.type + "." + mAdapter.method + "(" + viewExpression + ", " +
                    mCastString + valueExpression + ")";
        }

        @Override
        public int getMinApi() {
            return 1;
        }
    }

    public static class ModelMethodSetter extends SetterCall {
        final ModelMethod mModelMethod;

        public ModelMethodSetter(ModelMethod modelMethod) {
            mModelMethod = modelMethod;
        }

        @Override
        public String toJavaInternal(String viewExpression, String valueExpression) {
            return viewExpression + "." + mModelMethod.getName() + "(" + mCastString +
                    valueExpression + ")";
        }

        @Override
        public int getMinApi() {
            return mModelMethod.getMinApi();
        }
    }

    public static interface BindingSetterCall {
        String toJava(String viewExpression, String... valueExpressions);

        int getMinApi();
    }

    public static abstract class SetterCall implements BindingSetterCall {
        private MethodDescription mConverter;
        protected String mCastString = "";

        public SetterCall() {
        }

        public void setConverter(MethodDescription converter) {
            mConverter = converter;
        }

        protected abstract String toJavaInternal(String viewExpression, String converted);

        @Override
        public final String toJava(String viewExpression, String... valueExpression) {
            Preconditions.checkArgument(valueExpression.length == 1);
            return toJavaInternal(viewExpression, convertValue(valueExpression[0]));
        }

        protected String convertValue(String valueExpression) {
            return mConverter == null ? valueExpression :
                    mConverter.type + "." + mConverter.method + "(" + valueExpression + ")";
        }

        abstract public int getMinApi();

        public void setCast(ModelClass castTo) {
            mCastString = "(" + castTo.toJavaCode() + ") ";
        }
    }

    public static class MultiAttributeSetter implements BindingSetterCall {
        public final String[] attributes;
        private final MethodDescription mAdapter;
        private final MethodDescription[] mConverters;
        private final String[] mCasts;
        private final MultiValueAdapterKey mKey;

        public MultiAttributeSetter(MultiValueAdapterKey key, String[] attributes,
                MethodDescription adapter, MethodDescription[] converters, String[] casts) {
            Preconditions.checkArgument(converters != null &&
                    converters.length == attributes.length &&
                    casts != null && casts.length == attributes.length);
            this.attributes = attributes;
            this.mAdapter = adapter;
            this.mConverters = converters;
            this.mCasts = casts;
            this.mKey = key;
        }

        @Override
        public final String toJava(String viewExpression, String[] valueExpressions) {
            Preconditions.checkArgument(valueExpressions.length == attributes.length,
                    "MultiAttributeSetter needs %s items, received %s",
                    Arrays.toString(attributes), Arrays.toString(valueExpressions));
            StringBuilder sb = new StringBuilder();
            sb.append(mAdapter.type)
                    .append('.')
                    .append(mAdapter.method)
                    .append('(')
                    .append(viewExpression);
            for (int i = 0; i < valueExpressions.length; i++) {
                sb.append(',');
                if (mConverters[i] != null) {
                    final MethodDescription converter = mConverters[i];
                    sb.append(converter.type)
                            .append('.')
                            .append(converter.method)
                            .append('(')
                            .append(valueExpressions[i])
                            .append(')');
                } else {
                    if (mCasts[i] != null) {
                        sb.append('(')
                                .append(mCasts[i])
                                .append(')');
                    }
                    sb.append(valueExpressions[i]);
                }
            }
            sb.append(')');
            return sb.toString();
        }

        @Override
        public int getMinApi() {
            return 1;
        }

        @Override
        public String toString() {
            return "MultiAttributeSetter{" +
                    "attributes=" + Arrays.toString(attributes) +
                    ", mAdapter=" + mAdapter +
                    ", mConverters=" + Arrays.toString(mConverters) +
                    ", mCasts=" + Arrays.toString(mCasts) +
                    ", mKey=" + mKey +
                    '}';
        }
    }
}
