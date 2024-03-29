package zh1.liang.tiny.netty.channel;

import lombok.extern.slf4j.Slf4j;
import zh1.liang.tiny.netty.util.Attribute;
import zh1.liang.tiny.netty.util.AttributeKey;
import zh1.liang.tiny.netty.util.concurrent.EventExecutor;
import zh1.liang.tiny.netty.util.internal.ObjectUtil;
import zh1.liang.tiny.netty.util.internal.StringUtil;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * @author: zhe.liang
 * @create: 2023-09-21 18:00
 *
 * 出站处理器接口中定义了三个方法。这样一来，在寻找出站处理器的时候，怎么能事先知道用户重写了哪个方法呢？
 */
@Slf4j
public abstract class AbstractChannelHandlerContext implements ChannelHandlerContext {

    // ChannelHandler添加链表后的初始状态
    private static final int INIT = 0;
    private static final int ADD_PENDING = 1;
    private static final int ADD_COMPLETE = 2;
    private static final int REMOVE_COMPLETE = 3;

    private static final AtomicIntegerFieldUpdater<AbstractChannelHandlerContext> HANDLER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannelHandlerContext.class, "handlerState");

    volatile AbstractChannelHandlerContext next;

    volatile AbstractChannelHandlerContext prev;

    private volatile int handlerState = INIT;

    private final DefaultChannelPipeline pipeline;

    /**
     * @Description:ChannelHandler所对应的名字
     */
    private final String name;

    // 该值为false，ChannelHandler状态为ADD_PENDING的时候，也可以响应pipeline中的事件
    // 该值为true表示只有ChannelHandler的状态为ADD_COMPLETE时，才能响应pipeline中的事件
    private final boolean ordered;

    /** 关心事件掩码 */
    private final int executionMask;


    final EventExecutor executor;

    private ChannelFuture succeededFuture;

    protected AbstractChannelHandlerContext(
            DefaultChannelPipeline pipeline,
            EventExecutor executor,
            String name,
            Class<? extends ChannelHandler> handlerClass) {
        this.name = ObjectUtil.checkNotNull(name, "name");
        this.pipeline = pipeline;
        this.executor = executor;
        //计算出一个Class关心的事件
        this.executionMask = ChannelHandlerMask.mask(handlerClass);
        //todo
        ordered = executor == null;
    }

    @Override
    public Channel channel() {
        return pipeline.channel();
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public EventExecutor executor() {
        //如果用户没有指定执行器
        if (executor == null) {
            return channel().eventLoop();
        } else {
            return executor;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isRemoved() {
        return handlerState == REMOVE_COMPLETE;
    }


    /**
     * 找到下一个对registere事件感兴趣的ChannelHandler，registere事件就是handler中的channelRegistered方法，
     * 只要该方法被重写，就意味着该ChannelHandler对registere事件感兴趣。
     *
     * fire指的是触发，本ctx不会处理
     */
    @Override
    public ChannelHandlerContext fireChannelRegistered() {
        invokeChannelRegistered(findContextInbound(ChannelHandlerMask.MASK_CHANNEL_REGISTERED));
        return this;
    }

    /**
     * 执行该handler中的ChannelRegistered方法，从该方法可以看出，一旦channel绑定了单线程执行器，
     * 那么关于该channel的一切，都要由单线程执行器来执行和处理。如果当前调用方法的线程不是单线程执行器的线程，那就
     * 把要进行的动作封装为异步任务提交给执行器
     */
    static void invokeChannelRegistered(final AbstractChannelHandlerContext nextSelectedContext) {
        EventExecutor executor = nextSelectedContext.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            nextSelectedContext.invokeChannelRegistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    nextSelectedContext.invokeChannelRegistered();
                }
            });
        }
    }

    @Override
    public ChannelHandlerContext fireChannelUnregistered() {
        invokeChannelUnregistered(findContextInbound(ChannelHandlerMask.MASK_CHANNEL_REGISTERED));
        return this;
    }


    static void invokeChannelUnregistered(final AbstractChannelHandlerContext nextSelectedContext){
        EventExecutor executor = nextSelectedContext.executor;
        if(executor.inEventLoop(Thread.currentThread())){
            nextSelectedContext.invokeChannelRegistered();
        }else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    nextSelectedContext.invokeChannelRegistered();
                }
            });
        }
    }


    @Override
    public ChannelHandlerContext fireChannelActive() {
        invokeChannelActive(findContextInbound(ChannelHandlerMask.MASK_CHANNEL_ACTIVE));
        return this;
    }


    static void invokeChannelActive(final AbstractChannelHandlerContext nextSelectedContext) {
        EventExecutor executor = nextSelectedContext.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            nextSelectedContext.invokeChannelActive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    nextSelectedContext.invokeChannelActive();
                }
            });
        }
    }

    @Override
    public ChannelHandlerContext fireChannelInactive() {
        invokeChannelInactive(findContextInbound(ChannelHandlerMask.MASK_CHANNEL_INACTIVE));
        return this;
    }

    static void invokeChannelInactive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeChannelInactive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelInactive();
                }
            });
        }
    }

    private void invokeChannelInactive() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelInactive(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelInactive();
        }
    }


    @Override
    public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
        invokeExceptionCaught(findContextInbound(ChannelHandlerMask.MASK_EXCEPTION_CAUGHT), cause);
        return this;
    }

    static void invokeExceptionCaught(final AbstractChannelHandlerContext next, final Throwable cause) {
        ObjectUtil.checkNotNull(cause, "cause");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeExceptionCaught(cause);
        } else {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        next.invokeExceptionCaught(cause);
                    }
                });
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Failed to submit an exceptionCaught() event.", t);
                    log.warn("The exceptionCaught() event that was failed to submit was:", cause);
                }
            }
        }
    }



    @Override
    public ChannelHandlerContext fireUserEventTriggered(final Object event) {
        invokeUserEventTriggered(findContextInbound(ChannelHandlerMask.MASK_USER_EVENT_TRIGGERED), event);
        return this;
    }

    static void invokeUserEventTriggered(final AbstractChannelHandlerContext next, final Object event) {
        ObjectUtil.checkNotNull(event, "event");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeUserEventTriggered(event);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeUserEventTriggered(event);
                }
            });
        }
    }

    private void invokeUserEventTriggered(Object event) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).userEventTriggered(this, event);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireUserEventTriggered(event);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelRead(final Object msg) {
        AbstractChannelHandlerContext nextContext = findContextInbound(ChannelHandlerMask.MASK_CHANNEL_READ);
        invokeChannelRead(nextContext, msg);
        return this;
    }

    /**
     * 这个方法目前感觉只是为了封装一下线程池的选择逻辑
     */
    static void invokeChannelRead(final AbstractChannelHandlerContext context, Object msg) {
        final Object m = msg;
        EventExecutor executor = context.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            context.invokeChannelRead(m);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    context.invokeChannelRead(m);
                }
            });
        }
    }

    private void invokeChannelRead(Object msg) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRead(this, msg);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelRead(msg);
        }
    }



    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
        invokeChannelReadComplete(findContextInbound(ChannelHandlerMask.MASK_CHANNEL_READ_COMPLETE));
        return this;
    }

    static void invokeChannelReadComplete(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeChannelReadComplete();
        }
//        一般来说是不会走到下面这个分支的,所以先注释了,不必再引入更多的类
//        else {
//            Tasks tasks = next.invokeTasks;
//            if (tasks == null) {
//                next.invokeTasks = tasks = new Tasks(next);
//            }
//            executor.execute(tasks.invokeChannelReadCompleteTask);
//        }
    }

    private void invokeChannelReadComplete() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelReadComplete(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelReadComplete();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelWritabilityChanged() {
        invokeChannelWritabilityChanged(findContextInbound(ChannelHandlerMask.MASK_CHANNEL_WRITABILITY_CHANGED));
        return this;
    }

    static void invokeChannelWritabilityChanged(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeChannelWritabilityChanged();
        }
//        else {
//            Tasks tasks = next.invokeTasks;
//            if (tasks == null) {
//                next.invokeTasks = tasks = new Tasks(next);
//            }
//            executor.execute(tasks.invokeChannelWritableStateChangedTask);
//        }
    }

    private void invokeChannelWritabilityChanged() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelWritabilityChanged(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelWritabilityChanged();
        }
    }



    /**
     * 出站处理器中的方法
     * 每当我调用一个方法时，比如说就是服务端channel的绑定端口号的bind方法，调用链路会先从AbstractChannel类中开始，
     * 但是，channel拥有ChannelPipeline链表，链表中有一系列的处理器，所以调用链就会跑到ChannelPipeline中，然后从ChannelPipeline
     * 又跑到每一个ChannelHandler中，经过这些ChannelHandler的处理，调用链又会跑到channel的内部类Unsafe中(头尾节点处理)，再经过一系列的调用，
     * 最后来到NioServerSocketChannel中，执行真正的doBind方法。
     */
    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newPromise());
    }


    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newPromise());
    }

    @Override
    public ChannelFuture disconnect() {
        return disconnect(newPromise());
    }

    @Override
    public ChannelFuture close() {
        return close(newPromise());
    }

    @Override
    public ChannelFuture deregister() {
        return deregister(newPromise());
    }



    public ChannelFuture bind(final SocketAddress localAddress,final ChannelPromise promise){
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }

        AbstractChannelHandlerContext next = findContextOutbound(ChannelHandlerMask.MASK_BIND);
        EventExecutor executor = next.executor();
        if(executor.inEventLoop(Thread.currentThread())){
            next.invokeBind(localAddress,promise);
        }else {
            safeExecute(executor,new Runnable() {
                @Override
                public void run() {
                    next.invokeBind(localAddress,promise);
                }
            },promise,null);
        }
        return promise;
    }

    private void invokeBind(SocketAddress localAddress, ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                //每次都要调用handler()方法来获得handler，但是接口中的handler方法是在哪里实现的呢？
                //在DefaultChannelHandlerContext类中，这也提醒着我们，我们创建的context节点是DefaultChannelHandlerContext节点。
                ((ChannelOutboundHandler) handler()).bind(this, localAddress, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            bind(localAddress, promise);
        }
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return connect(remoteAddress, null, promise);
    }

    @Override
    public ChannelFuture connect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {

        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }
        final AbstractChannelHandlerContext next = findContextOutbound(ChannelHandlerMask.MASK_CONNECT);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeConnect(remoteAddress, localAddress, promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeConnect(remoteAddress, localAddress, promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeConnect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).connect(this, remoteAddress, localAddress, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            connect(remoteAddress, localAddress, promise);
        }
    }


    @Override
    public ChannelFuture disconnect(final ChannelPromise promise) {
        final AbstractChannelHandlerContext next = findContextOutbound(ChannelHandlerMask.MASK_DISCONNECT);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeDisconnect(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDisconnect(promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeDisconnect(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).disconnect(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            disconnect(promise);
        }
    }


    /**
     * @Author: PP-jessica
     * @Description:关闭连接的方法，这个方法会放在最后优雅停机和释放资源的时候讲解
     */
    @Override
    public ChannelFuture close(final ChannelPromise promise) {
        final AbstractChannelHandlerContext next = findContextOutbound(ChannelHandlerMask.MASK_CLOSE);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeClose(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeClose(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeClose(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).close(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            close(promise);
        }
    }


    @Override
    public ChannelFuture deregister(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            return promise;
        }
        final AbstractChannelHandlerContext next = findContextOutbound(ChannelHandlerMask.MASK_DEREGISTER);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeDeregister(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDeregister(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeDeregister(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).deregister(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            deregister(promise);
        }
    }




    @Override
    public ChannelHandlerContext read() {
        final AbstractChannelHandlerContext next = findContextOutbound(ChannelHandlerMask.MASK_READ);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeRead();
        }
//        else {
//            Tasks tasks = next.invokeTasks;
//            if (tasks == null) {
//                next.invokeTasks = tasks = new Tasks(next);
//            }
//            executor.execute(tasks.invokeReadTask);
//        }

        return this;
    }

    private void invokeRead() {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).read(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            read();
        }
    }



    /**
     * @Author: PP-jessica
     * @Description:write方法暂时也不讲解，等后面再讲解，其实，这时候大家差不多也能明白该方法的逻辑了。
     */
    @Override
    public ChannelFuture write(Object msg) {
        return write(msg, newPromise());
    }

    @Override
    public ChannelFuture write(final Object msg, final ChannelPromise promise) {
        write(msg, false, promise);

        return promise;
    }

    private void write(Object msg, boolean flush, ChannelPromise promise) {
        ObjectUtil.checkNotNull(msg, "msg");
        final AbstractChannelHandlerContext next = findContextOutbound(flush ?
                (ChannelHandlerMask.MASK_WRITE | ChannelHandlerMask.MASK_FLUSH) : ChannelHandlerMask.MASK_WRITE);
        final Object m = msg;
        //该方法用来检查内存是否泄漏，因为还未引入，所以暂时注释掉
        //final Object m = pipeline.touch(msg, next);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            if (flush) {
                //flush为true，所以进入这个分支
                next.invokeWriteAndFlush(m, promise);
            } else {
                next.invokeWrite(m, promise);
            }
        } else {
            //下面被注释掉的分支是源码，我们用的这个else分支是我自己写的，等真正讲到WriteAndFlush方法时，我们再讲解源码
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeWriteAndFlush(m, promise);
                }
            });
        }
//        else {
//            final AbstractWriteTask task;
//            if (flush) {
//                task = WriteAndFlushTask.newInstance(next, m, promise);
//            }  else {
//                task = WriteTask.newInstance(next, m, promise);
//            }
//            if (!safeExecute(executor, task, promise, m)) {
//                task.cancel();
//            }
//        }
    }

    private void invokeWrite(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
        } else {
            write(msg, promise);
        }
    }

    private void invokeWrite0(Object msg, ChannelPromise promise) {
        try {
            ((ChannelOutboundHandler) handler()).write(this, msg, promise);
        } catch (Throwable t) {
            notifyOutboundHandlerException(t, promise);
        }
    }


    @Override
    public ChannelHandlerContext flush() {
        final AbstractChannelHandlerContext next = findContextOutbound(ChannelHandlerMask.MASK_FLUSH);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop(Thread.currentThread())) {
            next.invokeFlush();
        }
//        else {
//            Tasks tasks = next.invokeTasks;
//            if (tasks == null) {
//                next.invokeTasks = tasks = new Tasks(next);
//            }
//            safeExecute(executor, tasks.invokeFlushTask, channel().voidPromise(), null);
//        }
        return this;
    }

    private void invokeFlush() {
        if (invokeHandler()) {
            //发送缓冲区的数据
            invokeFlush0();
        } else {
            flush();
        }
    }
    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return writeAndFlush(msg, newPromise());
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        write(msg, true, promise);
        return promise;
    }

    private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
            invokeFlush0();
        } else {
            writeAndFlush(msg, promise);
        }
    }


    private void invokeFlush0() {
        try {
            ((ChannelOutboundHandler) handler()).flush(this);
        } catch (Throwable t) {
            notifyHandlerException(t);
        }
    }


    /**
     * @Author: PP-jessica
     * @Description:该方法就可以得到用户存储在channel这个map中的数据，每一个handler都可以得到
     */
    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return channel().attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return channel().hasAttr(key);
    }


    @Override
    public ChannelPromise newPromise() {
        return new DefaultChannelPromise(channel(), executor());
    }

    /**
     * @Author: PP-jessica
     * @Description:该方法做了一点小改动，我没有引入SucceededChannelFuture类，不是核心方法，看看就行
     */
    @Override
    public ChannelFuture newSucceededFuture() {
        ChannelFuture succeededFuture = this.succeededFuture;
        if (succeededFuture == null) {
            this.succeededFuture = succeededFuture = new DefaultChannelPromise(channel(), executor());
        }
        return succeededFuture;
    }


    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        //return new FailedChannelFuture(channel(), executor(), cause);
        return null;
    }



    private AbstractChannelHandlerContext findContextInbound(int mask) {
        AbstractChannelHandlerContext ctx = this;
        do {
            //入站处理器，数据从前往后传输
            ctx = ctx.next;
        } while ((ctx.executionMask & mask) == 0);
        return ctx;
    }

    private AbstractChannelHandlerContext findContextOutbound(int mask){
        AbstractChannelHandlerContext ctx = this;

        do{
            ctx = ctx.prev;
        }while ((ctx.executionMask & mask) == 0);
        return ctx;

    }



    /**
     * @Author: PP-jessica
     * @Description:真正执行handler中的ChannelRegistered方法
     */
    private void invokeChannelRegistered() {
        //接下来会一直看见invokeHandler这个方法，这个方法就是判断CannelHandler在链表中的状态，只有是ADD_COMPLETE，
        //才会返回true，方法才能继续向下运行，如果返回false，那就进入else分支，会跳过该节点，寻找下一个可以处理数据的节点
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRegistered(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelRegistered();
        }
    }

    private void invokeChannelActive() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelActive(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelActive();
        }
    }


    private boolean invokeHandler() {
        int handlerState = this.handlerState;
        return handlerState == ADD_COMPLETE || (!ordered && handlerState == ADD_PENDING);
    }


    private void notifyHandlerException(Throwable cause) {
        if (inExceptionCaught(cause)) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "An exception was thrown by a user handler " +
                                "while handling an exceptionCaught event", cause);
            }
            return;
        }

        invokeExceptionCaught(cause);
    }

    private static boolean inExceptionCaught(Throwable cause) {
        do {
            StackTraceElement[] trace = cause.getStackTrace();
            if (trace != null) {
                for (StackTraceElement t : trace) {
                    if (t == null) {
                        break;
                    }
                    if ("exceptionCaught".equals(t.getMethodName())) {
                        return true;
                    }
                }
            }

            cause = cause.getCause();
        } while (cause != null);

        return false;
    }

    private void invokeExceptionCaught(final Throwable cause) {
        if (invokeHandler()) {
            try {
                handler().exceptionCaught(this, cause);
            } catch (Throwable error) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "An exception {}" +
                                    "was thrown by a user handler's exceptionCaught() " +
                                    "method while handling the following exception:",
                            //ThrowableUtil.stackTraceToString(error),
                            cause);
                } else if (log.isWarnEnabled()) {
                    log.warn(
                            "An exception '{}' [enable DEBUG level for full stacktrace] " +
                                    "was thrown by a user handler's exceptionCaught() " +
                                    "method while handling the following exception:", error, cause);
                }
            }
        } else {
            fireExceptionCaught(cause);
        }
    }

    private static boolean safeExecute(EventExecutor executor, Runnable runnable, ChannelPromise promise, Object msg) {
        try {
            executor.execute(runnable);
            return true;
        } catch (Throwable cause) {
            try {
                promise.setFailure(cause);
            } finally {
                if (msg != null) {
                    //当该引用计数减至为0时，该ByteBuf即可回收，我们还未讲到这里，所以我先注释掉这个方法
                    //ReferenceCountUtil.release(msg);
                }
            }
            return false;
        }
    }

    private static void notifyOutboundHandlerException(Throwable cause, ChannelPromise promise) {
        //PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }


    private boolean isNotValidPromise(ChannelPromise promise, boolean allowVoidPromise) {
        if (promise == null) {
            throw new NullPointerException("promise");
        }
        if (promise.isDone()) {
            if (promise.isCancelled()) {
                return true;
            }
            throw new IllegalArgumentException("promise already done: " + promise);
        }
        if (promise.channel() != channel()) {
            throw new IllegalArgumentException(String.format(
                    "promise.channel does not match: %s (expected: %s)", promise.channel(), channel()));
        }
        if (promise.getClass() == DefaultChannelPromise.class) {
            return false;
        }
        if (promise instanceof AbstractChannel.CloseFuture) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(AbstractChannel.CloseFuture.class) + " not allowed in a pipeline");
        }
        return false;
    }

    /**
     * @Author: PP-jessica
     * @Description:把链表中的ChannelHandler的状态设置为等待添加
     */
    final void setAddPending() {
        boolean updated = HANDLER_STATE_UPDATER.compareAndSet(this, INIT, ADD_PENDING);
        assert updated;
    }

    /**
     * @Author: PP-jessica
     * @Description:把链表中的ChannelHandler的状态设置为添加完成
     */
    final boolean setAddComplete() {
        for (;;) {
            int oldState = handlerState;
            if (oldState == REMOVE_COMPLETE) {
                return false;
            }
            if (HANDLER_STATE_UPDATER.compareAndSet(this, oldState, ADD_COMPLETE)) {
                return true;
            }
        }
    }

    /**
     * @Author: PP-jessica
     * @Description:把链表中的ChannelHandler的状态设置为删除完成
     */
    final void setRemoved() {
        handlerState = REMOVE_COMPLETE;
    }


    /**
     * @Author: PP-jessica
     * @Description:在该方法中，ChannelHandler的添加状态将变为添加完成，然后ChannelHandler调用它的
     * handlerAdded方法
     */
    final void callHandlerAdded() throws Exception {
        //在这里改变channelhandler的状态
        if (setAddComplete()) {
            handler().handlerAdded(this);
        }
    }

    /**
     * @Author: PP-jessica
     * @Description:回调链表中节点的handlerRemoved方法，该方法在ChannelPipeline中有节点被删除时被调用。
     */
    final void callHandlerRemoved() throws Exception {
        try {
            if (handlerState == ADD_COMPLETE) {
                handler().handlerRemoved(this);
            }
        } finally {
            setRemoved();
        }
    }

}
