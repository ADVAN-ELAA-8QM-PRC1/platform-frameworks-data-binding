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

package com.android.databinding.expr;

import com.android.databinding.ClassAnalyzer;

import java.util.List;
import java.util.Map;

public class BracketExpr extends Expr {

    public static enum BracketAccessor {
        ARRAY,
        LIST,
        MAP,
    }

    final private Expr mTarget;

    final private Expr mArg;

    private BracketAccessor mAccessor;

    BracketExpr(Expr target, Expr arg) {
        super(target, arg);
        mTarget = target;
        mArg = arg;
    }

    @Override
    protected Class resolveType(ClassAnalyzer classAnalyzer) {
        Class<?> targetType = mTarget.resolveType(classAnalyzer);
        if (targetType.isArray()) {
            mAccessor = BracketAccessor.ARRAY;
        } else if (List.class.isAssignableFrom(targetType)) {
            mAccessor = BracketAccessor.LIST;
        } else if (Map.class.isAssignableFrom(targetType)) {
            mAccessor = BracketAccessor.MAP;
        } else {
            throw new IllegalArgumentException("Cannot determine variable type used in [] " +
                    "expression. Cast the value to List, ObservableList, Map, " +
                    "Cursor, or array.");
        }
        if (targetType.isArray()) {
            return targetType.getComponentType();
        } else {
            return Object.class;
        }
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }

    protected String computeUniqueKey() {
        return sUniqueKeyJoiner.join(mTarget.computeUniqueKey(), "$", mArg.computeUniqueKey(), "$");
    }

    public Expr getTarget() {
        return mTarget;
    }

    public Expr getArg() {
        return mArg;
    }

    public BracketAccessor getAccessor() {
        return mAccessor;
    }

    public boolean argCastsInteger() {
        return Object.class.equals(mArg.getResolvedType());
    }
}
