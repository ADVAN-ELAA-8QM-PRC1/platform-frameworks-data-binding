package com.android.databinding.library;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Created by yboyar on 11/8/14.
 */
public class DataBinder {

    static DataBinderMapper sMapper;

    private WeakHashMap<View, ViewDataBinder> mDataBinderMap = new WeakHashMap<>();

    private SparseArray<WeakReference<ViewDataBinder>> mDataBinderById = new SparseArray<>();

    private static DataBinderMapper getMapper() {
        if (sMapper != null) {
            return sMapper;
        }
        try {
            sMapper = (DataBinderMapper) DataBinder.class.getClassLoader()
                    .loadClass(
                            "com.android.databinding.GeneratedDataBinderRenderer")
                    .newInstance();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return sMapper;
    }

    public static int convertToId(String key) {
        return getMapper().getId(key);
    }

    public View inflate(Context context, int layoutId) {
        View view = LayoutInflater.from(context).inflate(layoutId, null);
        ViewDataBinder dataBinder = getMapper().getDataBinder(view, layoutId);
        if (dataBinder != null) {
            mDataBinderMap.put(view, dataBinder);
            mDataBinderById.put(layoutId, new WeakReference<>(dataBinder));
        }
        return view;
    }

    public boolean setVariable(View view, String name, Object variable) {
        ViewDataBinder binder = getDataBinder(view);
        if (binder == null) {
            return false;
        }
        final int nameId = convertToId(name);
        if (nameId == -1) {
            return false;
        }
        return binder.setVariable(nameId, variable);
    }

    public boolean setVariable(int layoutId, String name, Object variable) {
        ViewDataBinder binder = getDataBinder(layoutId);
        if (binder == null) {
            return false;
        }
        final int nameId = convertToId(name);
        if (nameId == -1) {
            return false;
        }
        return binder.setVariable(nameId, variable);
    }

    public ViewDataBinder getDataBinder(View view) {
        return mDataBinderMap.get(view);
    }

    public <T> T getDataBinderI(Class<T> klass, View view) {
        return (T) getDataBinder(view);
    }

    public <T> T getDataBinderI(Class<T> klass, int layoutId) {
        return (T) getDataBinder(layoutId);
    }

    public static ViewDataBinder createBinder(Context context, int layoutId, ViewGroup parent) {
        return getMapper()
                .getDataBinder(LayoutInflater.from(context).inflate(layoutId, parent, false),
                        layoutId);
    }
    @SuppressWarnings("unchecked")
    public static <T> T createBinder(Class<T> klass, Context context, int layoutId, ViewGroup parent) {
        return (T) createBinder(context, layoutId, parent);
    }

    public ViewDataBinder getDataBinder(int layoutId) {
        WeakReference<ViewDataBinder> weakReference = mDataBinderById.get(layoutId);
        if (weakReference == null) {
            return null;
        }
        return weakReference.get();
    }
}
