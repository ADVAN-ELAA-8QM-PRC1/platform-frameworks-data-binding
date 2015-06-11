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
package android.databinding.testapp;

import android.databinding.testapp.databinding.FragmentTest3Binding;
import android.test.UiThreadTest;
import android.widget.TextView;

public class FragmentTest3 extends BaseDataBinderTest<FragmentTest3Binding> {

    public FragmentTest3() {
        super(FragmentTest3Binding.class);
    }

    @UiThreadTest
    public void testSimpleFragment() throws Throwable {
        FragmentTest3Binding binding = initBinder();
        assertNotNull(getActivity().getFragmentManager().findFragmentByTag("hello"));
        binding.setA("Hello");
        binding.setB(" World");
        binding.executePendingBindings();
        TextView textView = (TextView) binding.getRoot().findViewById(R.id.textView);
        assertEquals("Hello World", textView.getText().toString());
        assertEquals("hello", binding.fragment1.getTag());
    }
}
