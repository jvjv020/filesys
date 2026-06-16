package com.fmsy.converter;

import java.util.Iterator;

/**
 * 可关闭的迭代器封装 — 包装 Iterator 为 AutoCloseable，附带记录数统计。
 *
 * <p>P1 #6 优化:迭代过程中自动累加 {@link #getRecordCount()},
 * 消除 preAudit 和 parse 之间重复读文件的 I/O 开销。
 */
public class CloseableIterator<T> implements Iterator<T>, AutoCloseable {
    private final Iterator<T> delegate;
    private int recordCount = 0;

    public CloseableIterator(Iterator<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean hasNext() { return delegate.hasNext(); }

    @Override
    public T next() {
        T item = delegate.next();
        recordCount++;
        return item;
    }

    /** 返回迭代过程中已统计的记录数 */
    public int getRecordCount() {
        return recordCount;
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable ac) {
            ac.close();
        }
    }
}