package com.boc.nl2sql.model;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 仅在同一后台工作线程内传递可信任务约束，不接受模型参数。 */
public final class ModelCallContext implements AutoCloseable {
    private static final ThreadLocal<ModelCallContext> CURRENT=new ThreadLocal<>();
    private final ModelCallContext previous;
    private final BooleanSupplier active; private final Supplier<String> customer;
    private ModelCallContext(BooleanSupplier active,Supplier<String> customer){this.previous=CURRENT.get();this.active=active;this.customer=customer;CURRENT.set(this);}
    public static ModelCallContext open(BooleanSupplier active,Supplier<String> customer){return new ModelCallContext(active,customer);}
    public static boolean active(){return CURRENT.get()==null || CURRENT.get().active.getAsBoolean();}
    public static String customer(){return CURRENT.get()==null?null:CURRENT.get().customer.get();}
    @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
