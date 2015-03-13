/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.testapp;

import com.android.databinding.testapp.BaseDataBinderTest;
import com.android.databinding.testapp.R;
import com.android.databinding.testapp.generated.ObservableWithNotBindableFieldBinder;
import com.android.databinding.testapp.vo.ObservableWithNotBindableFieldObject;

import android.test.UiThreadTest;

public class ObservableWithNotBindableFieldObjectTest extends BaseDataBinderTest<ObservableWithNotBindableFieldBinder> {


    public ObservableWithNotBindableFieldObjectTest() {
        super(ObservableWithNotBindableFieldBinder.class, R.layout.observable_with_not_bindable_field);
    }

    @UiThreadTest
    public void testSimple() {
        ObservableWithNotBindableFieldObject obj = new ObservableWithNotBindableFieldObject();
        mBinder.setObj(obj);
        mBinder.rebindDirty();
        assertEquals("", mBinder.getTextView().getText().toString());
        obj.update("100");
        mBinder.rebindDirty();
        assertEquals("100", mBinder.getTextView().getText().toString());
    }
}
