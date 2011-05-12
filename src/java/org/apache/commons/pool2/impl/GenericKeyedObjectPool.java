/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.pool2.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.pool2.BaseKeyedObjectPool;
import org.apache.commons.pool2.KeyedPoolableObjectFactory;
import org.apache.commons.pool2.PoolUtils;

/**
 * A configurable <code>KeyedObjectPool</code> implementation.
 * <p>
 * When coupled with the appropriate {@link KeyedPoolableObjectFactory},
 * <code>GenericKeyedObjectPool</code> provides robust pooling functionality for
 * keyed objects. A <code>GenericKeyedObjectPool</code> can be viewed as a map
 * of pools, keyed on the (unique) key values provided to the
 * {@link #preparePool preparePool}, {@link #addObject addObject} or
 * {@link #borrowObject borrowObject} methods. Each time a new key value is
 * provided to one of these methods, a new pool is created under the given key
 * to be managed by the containing <code>GenericKeyedObjectPool.</code>
 * </p>
 * <p>A <code>GenericKeyedObjectPool</code> provides a number of configurable
 * parameters:</p>
 * <ul>
 *  <li>
 *    {@link #setMaxActive maxActive} controls the maximum number of objects
 *    (per key) that can allocated by the pool (checked out to client threads,
 *    or idle in the pool) at one time.  When non-positive, there is no limit
 *    to the number of objects per key. When {@link #setMaxActive maxActive} is
 *    reached, the keyed pool is said to be exhausted.  The default setting for
 *    this parameter is 8.
 *  </li>
 *  <li>
 *    {@link #setMaxTotal maxTotal} sets a global limit on the number of objects
 *    that can be in circulation (active or idle) within the combined set of
 *    pools.  When non-positive, there is no limit to the total number of
 *    objects in circulation. When {@link #setMaxTotal maxTotal} is exceeded,
 *    all keyed pools are exhausted. When <code>maxTotal</code> is set to a
 *    positive value and {@link #borrowObject borrowObject} is invoked
 *    when at the limit with no idle instances available, an attempt is made to
 *    create room by clearing the oldest 15% of the elements from the keyed
 *    pools. The default setting for this parameter is -1 (no limit).
 *  </li>
 *  <li>
 *    {@link #setMaxIdle maxIdle} controls the maximum number of objects that can
 *    sit idle in the pool (per key) at any time.  When negative, there
 *    is no limit to the number of objects that may be idle per key. The
 *    default setting for this parameter is 8.
 *  </li>
 *  <li>
 *    {@link #setWhenExhaustedAction whenExhaustedAction} specifies the
 *    behavior of the {@link #borrowObject borrowObject} method when a keyed
 *    pool is exhausted:
 *    <ul>
 *    <li>
 *      When {@link #setWhenExhaustedAction whenExhaustedAction} is
 *      {@link #WHEN_EXHAUSTED_FAIL}, {@link #borrowObject borrowObject} will throw
 *      a {@link NoSuchElementException}
 *    </li>
 *    <li>
 *      When {@link #setWhenExhaustedAction whenExhaustedAction} is
 *      {@link #WHEN_EXHAUSTED_GROW}, {@link #borrowObject borrowObject} will create a new
 *      object and return it (essentially making {@link #setMaxActive maxActive}
 *      meaningless.)
 *    </li>
 *    <li>
 *      When {@link #setWhenExhaustedAction whenExhaustedAction}
 *      is {@link #WHEN_EXHAUSTED_BLOCK}, {@link #borrowObject borrowObject} will block
 *      (invoke {@link Object#wait() wait} until a new or idle object is available.
 *      If a positive {@link #setMaxWait maxWait}
 *      value is supplied, the {@link #borrowObject borrowObject} will block for at
 *      most that many milliseconds, after which a {@link NoSuchElementException}
 *      will be thrown.  If {@link #setMaxWait maxWait} is non-positive,
 *      the {@link #borrowObject borrowObject} method will block indefinitely.
 *    </li>
 *    </ul>
 *    The default <code>whenExhaustedAction</code> setting is
 *    {@link #WHEN_EXHAUSTED_BLOCK}.
 *  </li>
 *  <li>
 *    When {@link #setTestOnBorrow testOnBorrow} is set, the pool will
 *    attempt to validate each object before it is returned from the
 *    {@link #borrowObject borrowObject} method. (Using the provided factory's
 *    {@link KeyedPoolableObjectFactory#validateObject validateObject} method.)
 *    Objects that fail to validate will be dropped from the pool, and a
 *    different object will be borrowed. The default setting for this parameter
 *    is <code>false.</code>
 *  </li>
 *  <li>
 *    When {@link #setTestOnReturn testOnReturn} is set, the pool will
 *    attempt to validate each object before it is returned to the pool in the
 *    {@link #returnObject returnObject} method. (Using the provided factory's
 *    {@link KeyedPoolableObjectFactory#validateObject validateObject}
 *    method.)  Objects that fail to validate will be dropped from the pool.
 *    The default setting for this parameter is <code>false.</code>
 *  </li>
 * </ul>
 * <p>
 * Optionally, one may configure the pool to examine and possibly evict objects
 * as they sit idle in the pool and to ensure that a minimum number of idle
 * objects is maintained for each key. This is performed by an
 * "idle object eviction" thread, which runs asynchronously. Caution should be
 * used when configuring this optional feature. Eviction runs contend with client
 * threads for access to objects in the pool, so if they run too frequently
 * performance issues may result.  The idle object eviction thread may be
 * configured using the following attributes:
 * <ul>
 *  <li>
 *   {@link #setTimeBetweenEvictionRunsMillis timeBetweenEvictionRunsMillis}
 *   indicates how long the eviction thread should sleep before "runs" of examining
 *   idle objects.  When non-positive, no eviction thread will be launched. The
 *   default setting for this parameter is -1 (i.e., by default, idle object
 *   eviction is disabled).
 *  </li>
 *  <li>
 *   {@link #setMinEvictableIdleTimeMillis minEvictableIdleTimeMillis}
 *   specifies the minimum amount of time that an object may sit idle in the
 *   pool before it is eligible for eviction due to idle time.  When
 *   non-positive, no object will be dropped from the pool due to idle time
 *   alone.  This setting has no effect unless
 *   <code>timeBetweenEvictionRunsMillis > 0.</code>  The default setting
 *   for this parameter is 30 minutes.
 *  </li>
 *  <li>
 *   {@link #setTestWhileIdle testWhileIdle} indicates whether or not idle
 *   objects should be validated using the factory's
 *   {@link KeyedPoolableObjectFactory#validateObject validateObject} method
 *   during idle object eviction runs.  Objects that fail to validate will be
 *   dropped from the pool. This setting has no effect unless
 *   <code>timeBetweenEvictionRunsMillis > 0.</code>  The default setting
 *   for this parameter is <code>false.</code>
 *  </li>
 *  <li>
 *    {@link #setMinIdle minIdle} sets a target value for the minimum number of
 *    idle objects (per key) that should always be available. If this parameter
 *    is set to a positive number and
 *    <code>timeBetweenEvictionRunsMillis > 0,</code> each time the idle object
 *    eviction thread runs, it will try to create enough idle instances so that
 *    there will be <code>minIdle</code> idle instances available under each
 *    key. This parameter is also used by {@link #preparePool preparePool}
 *    if <code>true</code> is provided as that method's
 *    <code>populateImmediately</code> parameter. The default setting for this
 *    parameter is 0.
 *  </li>
 * </ul>
 * <p>
 * The pools can be configured to behave as LIFO queues with respect to idle
 * objects - always returning the most recently used object from the pool,
 * or as FIFO queues, where borrowObject always returns the oldest object
 * in the idle object pool.
 * <ul>
 *  <li>
 *   {@link #setLifo <i>Lifo</i>}
 *   determines whether or not the pools return idle objects in
 *   last-in-first-out order. The default setting for this parameter is
 *   <code>true.</code>
 *  </li>
 * </ul>
 * <p>
 * GenericKeyedObjectPool is not usable without a {@link KeyedPoolableObjectFactory}.  A
 * non-<code>null</code> factory must be provided either as a constructor argument
 * or via a call to {@link #setFactory setFactory} before the pool is used.
 * </p>
 * <p>
 * Implementation note: To prevent possible deadlocks, care has been taken to
 * ensure that no call to a factory method will occur within a synchronization
 * block. See POOL-125 and DBCP-44 for more information.
 * </p>
 * @see GenericObjectPool
 *
 * @param <K> The type of keys maintained by this pool.
 * @param <T> Type of element pooled in this pool.
 *
 * @author Rodney Waldhoff
 * @author Dirk Verbeeck
 * @author Sandy McArthur
 * @version $Revision$ $Date$
 * @since Pool 1.0
 */
public class GenericKeyedObjectPool<K,T> extends BaseKeyedObjectPool<K,T>  {

    //--- public constants -------------------------------------------

    /**
     * The default cap on the number of idle instances (per key) in the pool.
     * @see #getMaxIdle
     * @see #setMaxIdle
     */
    public static final int DEFAULT_MAX_IDLE  = 8;

    /**
     * The default cap on the total number of active instances (per key)
     * from the pool.
     * @see #getMaxActive
     * @see #setMaxActive
     */
    public static final int DEFAULT_MAX_ACTIVE  = 8;

    /**
     * The default cap on the the overall maximum number of objects that can
     * exist at one time.
     * @see #getMaxTotal
     * @see #setMaxTotal
     */
    public static final int DEFAULT_MAX_TOTAL  = -1;

    /**
     * The default "when exhausted action" for the pool.
     * @see #setWhenExhaustedAction
     */
    public static final WhenExhaustedAction DEFAULT_WHEN_EXHAUSTED_ACTION =
        WhenExhaustedAction.BLOCK;

    /**
     * The default maximum amount of time (in milliseconds) the
     * {@link #borrowObject} method should block before throwing
     * an exception when the pool is exhausted and the
     * {@link #getWhenExhaustedAction "when exhausted" action} is
     * {@link #WHEN_EXHAUSTED_BLOCK}.
     * @see #getMaxWait
     * @see #setMaxWait
     */
    public static final long DEFAULT_MAX_WAIT = -1L;

    /**
     * The default "test on borrow" value.
     * @see #getTestOnBorrow
     * @see #setTestOnBorrow
     */
    public static final boolean DEFAULT_TEST_ON_BORROW = false;

    /**
     * The default "test on return" value.
     * @see #getTestOnReturn
     * @see #setTestOnReturn
     */
    public static final boolean DEFAULT_TEST_ON_RETURN = false;

    /**
     * The default "test while idle" value.
     * @see #getTestWhileIdle
     * @see #setTestWhileIdle
     * @see #getTimeBetweenEvictionRunsMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public static final boolean DEFAULT_TEST_WHILE_IDLE = false;

    /**
     * The default "time between eviction runs" value.
     * @see #getTimeBetweenEvictionRunsMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public static final long DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS = -1L;

    /**
     * The default number of objects to examine per run in the
     * idle object evictor.
     * @see #getNumTestsPerEvictionRun
     * @see #setNumTestsPerEvictionRun
     * @see #getTimeBetweenEvictionRunsMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public static final int DEFAULT_NUM_TESTS_PER_EVICTION_RUN = 3;

    /**
     * The default value for {@link #getMinEvictableIdleTimeMillis}.
     * @see #getMinEvictableIdleTimeMillis
     * @see #setMinEvictableIdleTimeMillis
     */
    public static final long DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS = 1000L * 60L * 30L;

    /**
     * The default minimum level of idle objects in the pool.
     * @since Pool 1.3
     * @see #setMinIdle
     * @see #getMinIdle
     */
    public static final int DEFAULT_MIN_IDLE = 0;

    /**
     * The default LIFO status. True means that borrowObject returns the
     * most recently used ("last in") idle object in a pool (if there are
     * idle instances available).  False means that pools behave as FIFO
     * queues - objects are taken from idle object pools in the order that
     * they are returned.
     * @see #setLifo
     */
    public static final boolean DEFAULT_LIFO = true;

    //--- constructors -----------------------------------------------

    /**
     * Create a new <code>GenericKeyedObjectPool</code> with no factory.
     *
     * @see #GenericKeyedObjectPool(KeyedPoolableObjectFactory)
     * @see #setFactory(KeyedPoolableObjectFactory)
     */
    public GenericKeyedObjectPool() {
        this(null, DEFAULT_MAX_ACTIVE, DEFAULT_WHEN_EXHAUSTED_ACTION, DEFAULT_MAX_WAIT, DEFAULT_MAX_IDLE, 
                DEFAULT_TEST_ON_BORROW, DEFAULT_TEST_ON_RETURN, DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                DEFAULT_NUM_TESTS_PER_EVICTION_RUN, DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS, DEFAULT_TEST_WHILE_IDLE);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy
     * objects if not <code>null</code>
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory) {
        this(factory, DEFAULT_MAX_ACTIVE, DEFAULT_WHEN_EXHAUSTED_ACTION, DEFAULT_MAX_WAIT, DEFAULT_MAX_IDLE,
                DEFAULT_TEST_ON_BORROW, DEFAULT_TEST_ON_RETURN, DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                DEFAULT_NUM_TESTS_PER_EVICTION_RUN, DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS, DEFAULT_TEST_WHILE_IDLE);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param config a non-<code>null</code> {@link GenericKeyedObjectPool.Config} describing the configuration
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, GenericKeyedObjectPool.Config config) {
        this(factory, config.maxActive, config.whenExhaustedAction, config.maxWait, config.maxIdle, config.maxTotal,
                config.minIdle, config.testOnBorrow, config.testOnReturn, config.timeBetweenEvictionRunsMillis,
                config.numTestsPerEvictionRun, config.minEvictableIdleTimeMillis, config.testWhileIdle, config.lifo);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed from me at one time (see {@link #setMaxActive})
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive) {
        this(factory,maxActive, DEFAULT_WHEN_EXHAUSTED_ACTION, DEFAULT_MAX_WAIT, DEFAULT_MAX_IDLE,
                DEFAULT_TEST_ON_BORROW, DEFAULT_TEST_ON_RETURN, DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS, 
                DEFAULT_NUM_TESTS_PER_EVICTION_RUN, DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS, DEFAULT_TEST_WHILE_IDLE);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed from me at one time (see {@link #setMaxActive})
     * @param whenExhaustedAction the action to take when the pool is exhausted (see {@link #setWhenExhaustedAction})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     *  <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #setMaxWait})
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait) {
        this(factory, maxActive, whenExhaustedAction, maxWait, DEFAULT_MAX_IDLE, DEFAULT_TEST_ON_BORROW,
                DEFAULT_TEST_ON_RETURN, DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS, DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS, DEFAULT_TEST_WHILE_IDLE);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed from me at one time (see {@link #setMaxActive})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     * <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #setMaxWait})
     * @param whenExhaustedAction the action to take when the pool is exhausted (see {@link #setWhenExhaustedAction})
     * @param testOnBorrow whether or not to validate objects before they are returned by the {@link #borrowObject}
     * method (see {@link #setTestOnBorrow})
     * @param testOnReturn whether or not to validate objects after they are returned to the {@link #returnObject}
     * method (see {@link #setTestOnReturn})
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait, boolean testOnBorrow, boolean testOnReturn) {
        this(factory, maxActive, whenExhaustedAction, maxWait, DEFAULT_MAX_IDLE,testOnBorrow,testOnReturn,
                DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS, DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS, DEFAULT_TEST_WHILE_IDLE);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed from me at one time
     * (see {@link #setMaxActive})
     * @param whenExhaustedAction the action to take when the pool is exhausted (see {@link #setWhenExhaustedAction})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     * <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #setMaxWait})
     * @param maxIdle the maximum number of idle objects in my pool (see {@link #setMaxIdle})
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait, int maxIdle) {
        this(factory, maxActive, whenExhaustedAction, maxWait, maxIdle, DEFAULT_TEST_ON_BORROW, DEFAULT_TEST_ON_RETURN,
                DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS, DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS, DEFAULT_TEST_WHILE_IDLE);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed from me at one time
     * (see {@link #setMaxActive})
     * @param whenExhaustedAction the action to take when the pool is exhausted (see {@link #setWhenExhaustedAction})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     * <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #getMaxWait})
     * @param maxIdle the maximum number of idle objects in my pool (see {@link #setMaxIdle})
     * @param testOnBorrow whether or not to validate objects before they are returned by the {@link #borrowObject}
     * method (see {@link #setTestOnBorrow})
     * @param testOnReturn whether or not to validate objects after they are returned to the {@link #returnObject}
     * method (see {@link #setTestOnReturn})
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait, int maxIdle, boolean testOnBorrow, boolean testOnReturn) {
        this(factory, maxActive, whenExhaustedAction, maxWait, maxIdle, testOnBorrow, testOnReturn,
                DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS, DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS, DEFAULT_TEST_WHILE_IDLE);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed from me at one time
     * (see {@link #setMaxActive})
     * @param whenExhaustedAction the action to take when the pool is exhausted 
     * (see {@link #setWhenExhaustedAction})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     * <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #setMaxWait})
     * @param maxIdle the maximum number of idle objects in my pool (see {@link #setMaxIdle})
     * @param testOnBorrow whether or not to validate objects before they are returned by the {@link #borrowObject}
     * method (see {@link #setTestOnBorrow})
     * @param testOnReturn whether or not to validate objects after they are returned to the {@link #returnObject}
     * method (see {@link #setTestOnReturn})
     * @param timeBetweenEvictionRunsMillis the amount of time (in milliseconds) to sleep between examining idle
     * objects for eviction (see {@link #setTimeBetweenEvictionRunsMillis})
     * @param numTestsPerEvictionRun the number of idle objects to examine per run within the idle object eviction
     * thread (if any) (see {@link #setNumTestsPerEvictionRun})
     * @param minEvictableIdleTimeMillis the minimum number of milliseconds an object can sit idle in the pool before
     * it is eligible for eviction (see {@link #setMinEvictableIdleTimeMillis})
     * @param testWhileIdle whether or not to validate objects in the idle object eviction thread, if any
     * (see {@link #setTestWhileIdle})
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait, int maxIdle, boolean testOnBorrow, boolean testOnReturn, long timeBetweenEvictionRunsMillis,
            int numTestsPerEvictionRun, long minEvictableIdleTimeMillis, boolean testWhileIdle) {
        this(factory, maxActive, whenExhaustedAction, maxWait, maxIdle, GenericKeyedObjectPool.DEFAULT_MAX_TOTAL,
                testOnBorrow, testOnReturn, timeBetweenEvictionRunsMillis, numTestsPerEvictionRun,
                minEvictableIdleTimeMillis, testWhileIdle);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed from me at one time
     * (see {@link #setMaxActive})
     * @param whenExhaustedAction the action to take when the pool is exhausted (see {@link #setWhenExhaustedAction})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     * <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #setMaxWait})
     * @param maxIdle the maximum number of idle objects in my pool (see {@link #setMaxIdle})
     * @param maxTotal the maximum number of objects that can exists at one time (see {@link #setMaxTotal})
     * @param testOnBorrow whether or not to validate objects before they are returned by the {@link #borrowObject}
     * method (see {@link #setTestOnBorrow})
     * @param testOnReturn whether or not to validate objects after they are returned to the {@link #returnObject}
     * method (see {@link #setTestOnReturn})
     * @param timeBetweenEvictionRunsMillis the amount of time (in milliseconds) to sleep between examining idle
     * objects for eviction (see {@link #setTimeBetweenEvictionRunsMillis})
     * @param numTestsPerEvictionRun the number of idle objects to examine per run within the idle object eviction
     * thread (if any) (see {@link #setNumTestsPerEvictionRun})
     * @param minEvictableIdleTimeMillis the minimum number of milliseconds an object can sit idle in the pool
     * before it is eligible for eviction (see {@link #setMinEvictableIdleTimeMillis})
     * @param testWhileIdle whether or not to validate objects in the idle object eviction thread, if any
     * (see {@link #setTestWhileIdle})
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait, int maxIdle, int maxTotal, boolean testOnBorrow, boolean testOnReturn,
            long timeBetweenEvictionRunsMillis, int numTestsPerEvictionRun, long minEvictableIdleTimeMillis,
            boolean testWhileIdle) {
        this(factory, maxActive, whenExhaustedAction, maxWait, maxIdle, maxTotal,
                GenericKeyedObjectPool.DEFAULT_MIN_IDLE, testOnBorrow, testOnReturn, timeBetweenEvictionRunsMillis,
                numTestsPerEvictionRun, minEvictableIdleTimeMillis, testWhileIdle);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed at one time (see {@link #setMaxActive})
     * @param whenExhaustedAction the action to take when the pool is exhausted (see {@link #setWhenExhaustedAction})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     * <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #setMaxWait})
     * @param maxIdle the maximum number of idle objects in my pool (see {@link #setMaxIdle})
     * @param maxTotal the maximum number of objects that can exists at one time (see {@link #setMaxTotal})
     * @param minIdle the minimum number of idle objects to have in the pool at any one time (see {@link #setMinIdle})
     * @param testOnBorrow whether or not to validate objects before they are returned by the {@link #borrowObject}
     * method (see {@link #setTestOnBorrow})
     * @param testOnReturn whether or not to validate objects after they are returned to the {@link #returnObject}
     * method (see {@link #setTestOnReturn})
     * @param timeBetweenEvictionRunsMillis the amount of time (in milliseconds) to sleep between examining idle
     * objects
     * for eviction (see {@link #setTimeBetweenEvictionRunsMillis})
     * @param numTestsPerEvictionRun the number of idle objects to examine per run within the idle object eviction
     * thread (if any) (see {@link #setNumTestsPerEvictionRun})
     * @param minEvictableIdleTimeMillis the minimum number of milliseconds an object can sit idle in the pool before
     * it is eligible for eviction (see {@link #setMinEvictableIdleTimeMillis})
     * @param testWhileIdle whether or not to validate objects in the idle object eviction thread, if any
     * (see {@link #setTestWhileIdle})
     * @since Pool 1.3
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait, int maxIdle, int maxTotal, int minIdle, boolean testOnBorrow, boolean testOnReturn,
            long timeBetweenEvictionRunsMillis, int numTestsPerEvictionRun, long minEvictableIdleTimeMillis,
            boolean testWhileIdle) {
        this(factory, maxActive, whenExhaustedAction, maxWait, maxIdle, maxTotal, minIdle, testOnBorrow, testOnReturn,
                timeBetweenEvictionRunsMillis, numTestsPerEvictionRun, minEvictableIdleTimeMillis, testWhileIdle,
                DEFAULT_LIFO);
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using the specified values.
     * @param factory the <code>KeyedPoolableObjectFactory</code> to use to create, validate, and destroy objects
     * if not <code>null</code>
     * @param maxActive the maximum number of objects that can be borrowed at one time
     *  (see {@link #setMaxActive})
     * @param whenExhaustedAction the action to take when the pool is exhausted (see {@link #setWhenExhaustedAction})
     * @param maxWait the maximum amount of time to wait for an idle object when the pool is exhausted and
     * <code>whenExhaustedAction</code> is {@link #WHEN_EXHAUSTED_BLOCK} (otherwise ignored) (see {@link #setMaxWait})
     * @param maxIdle the maximum number of idle objects in my pool (see {@link #setMaxIdle})
     * @param maxTotal the maximum number of objects that can exists at one time (see {@link #setMaxTotal})
     * @param minIdle the minimum number of idle objects to have in the pool at any one time (see {@link #setMinIdle})
     * @param testOnBorrow whether or not to validate objects before they are returned by the {@link #borrowObject}
     * method (see {@link #setTestOnBorrow})
     * @param testOnReturn whether or not to validate objects after they are returned to the {@link #returnObject}
     * method (see {@link #setTestOnReturn})
     * @param timeBetweenEvictionRunsMillis the amount of time (in milliseconds) to sleep between examining idle
     * objects for eviction (see {@link #setTimeBetweenEvictionRunsMillis})
     * @param numTestsPerEvictionRun the number of idle objects to examine per run within the idle object eviction
     * thread (if any) (see {@link #setNumTestsPerEvictionRun})
     * @param minEvictableIdleTimeMillis the minimum number of milliseconds an object can sit idle in the pool before
     * it is eligible for eviction (see {@link #setMinEvictableIdleTimeMillis})
     * @param testWhileIdle whether or not to validate objects in the idle object eviction thread, if any
     * (see {@link #setTestWhileIdle})
     * @param lifo whether or not the pools behave as LIFO (last in first out) queues (see {@link #setLifo})
     * @since Pool 1.4
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory, int maxActive, WhenExhaustedAction whenExhaustedAction,
            long maxWait, int maxIdle, int maxTotal, int minIdle, boolean testOnBorrow, boolean testOnReturn,
            long timeBetweenEvictionRunsMillis, int numTestsPerEvictionRun, long minEvictableIdleTimeMillis,
            boolean testWhileIdle, boolean lifo) {
        _factory = factory;
        _maxActive = maxActive;
        _lifo = lifo;
        _whenExhaustedAction = whenExhaustedAction;
        _maxWait = maxWait;
        _maxIdle = maxIdle;
        _maxTotal = maxTotal;
        _minIdle = minIdle;
        _testOnBorrow = testOnBorrow;
        _testOnReturn = testOnReturn;
        _timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        _numTestsPerEvictionRun = numTestsPerEvictionRun;
        _minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
        _testWhileIdle = testWhileIdle;

        startEvictor(_timeBetweenEvictionRunsMillis);
    }

    //--- public methods ---------------------------------------------

    //--- configuration methods --------------------------------------

    /**
     * Returns the cap on the number of object instances allocated by the pool
     * (checked out or idle),  per key.
     * A negative value indicates no limit.
     *
     * @return the cap on the number of active instances per key.
     * @see #setMaxActive
     */
    public int getMaxActive() {
        return _maxActive;
    }

    /**
     * Sets the cap on the number of object instances managed by the pool per key.
     * @param maxActive The cap on the number of object instances per key.
     * Use a negative value for no limit.
     *
     * @see #getMaxActive
     */
    public void setMaxActive(int maxActive) {
        _maxActive = maxActive;
    }

    /**
     * Returns the overall maximum number of objects (across pools) that can
     * exist at one time. A negative value indicates no limit.
     * @return the maximum number of instances in circulation at one time.
     * @see #setMaxTotal
     */
    public int getMaxTotal() {
        return _maxTotal;
    }

    /**
     * Sets the cap on the total number of instances from all pools combined.
     * When <code>maxTotal</code> is set to a
     * positive value and {@link #borrowObject borrowObject} is invoked
     * when at the limit with no idle instances available, an attempt is made to
     * create room by clearing the oldest 15% of the elements from the keyed
     * pools.
     *
     * @param maxTotal The cap on the total number of instances across pools.
     * Use a negative value for no limit.
     * @see #getMaxTotal
     */
    public void setMaxTotal(int maxTotal) {
        _maxTotal = maxTotal;
    }

    /**
     * Returns the action to take when the {@link #borrowObject} method
     * is invoked when the pool is exhausted (the maximum number
     * of "active" objects has been reached).
     *
     * @return the action to take when exhausted
     * @see #setWhenExhaustedAction
     */
    public WhenExhaustedAction getWhenExhaustedAction() {
        return _whenExhaustedAction;
    }

    /**
     * Sets the action to take when the {@link #borrowObject} method
     * is invoked when the pool is exhausted (the maximum number
     * of "active" objects has been reached).
     *
     * @param the action to take when exhausted
     * @see #getWhenExhaustedAction
     */
    public void setWhenExhaustedAction(WhenExhaustedAction whenExhaustedAction) {
        _whenExhaustedAction = whenExhaustedAction;
    }


    /**
     * Returns the maximum amount of time (in milliseconds) the
     * {@link #borrowObject} method should block before throwing
     * an exception when the pool is exhausted and the
     * {@link #setWhenExhaustedAction "when exhausted" action} is
     * {@link #WHEN_EXHAUSTED_BLOCK}.
     *
     * When less than or equal to 0, the {@link #borrowObject} method
     * may block indefinitely.
     *
     * @return the maximum number of milliseconds borrowObject will block.
     * @see #setMaxWait
     * @see #setWhenExhaustedAction
     * @see #WHEN_EXHAUSTED_BLOCK
     */
    public long getMaxWait() {
        return _maxWait;
    }

    /**
     * Sets the maximum amount of time (in milliseconds) the
     * {@link #borrowObject} method should block before throwing
     * an exception when the pool is exhausted and the
     * {@link #setWhenExhaustedAction "when exhausted" action} is
     * {@link #WHEN_EXHAUSTED_BLOCK}.
     *
     * When less than or equal to 0, the {@link #borrowObject} method
     * may block indefinitely.
     *
     * @param maxWait the maximum number of milliseconds borrowObject will block or negative for indefinitely.
     * @see #getMaxWait
     * @see #setWhenExhaustedAction
     * @see #WHEN_EXHAUSTED_BLOCK
     */
    public void setMaxWait(long maxWait) {
        _maxWait = maxWait;
    }

    /**
     * Returns the cap on the number of "idle" instances per key.
     * @return the maximum number of "idle" instances that can be held
     * in a given keyed pool.
     * @see #setMaxIdle
     */
    public int getMaxIdle() {
        return _maxIdle;
    }

    /**
     * Sets the cap on the number of "idle" instances in the pool.
     * If maxIdle is set too low on heavily loaded systems it is possible you
     * will see objects being destroyed and almost immediately new objects
     * being created. This is a result of the active threads momentarily
     * returning objects faster than they are requesting them them, causing the
     * number of idle objects to rise above maxIdle. The best value for maxIdle
     * for heavily loaded system will vary but the default is a good starting
     * point.
     * @param maxIdle the maximum number of "idle" instances that can be held
     * in a given keyed pool. Use a negative value for no limit.
     * @see #getMaxIdle
     * @see #DEFAULT_MAX_IDLE
     */
    public void setMaxIdle(int maxIdle) {
        _maxIdle = maxIdle;
    }

    /**
     * Sets the minimum number of idle objects to maintain in each of the keyed
     * pools. This setting has no effect unless
     * <code>timeBetweenEvictionRunsMillis > 0</code> and attempts to ensure
     * that each pool has the required minimum number of instances are only
     * made during idle object eviction runs.
     * @param poolSize - The minimum size of the each keyed pool
     * @since Pool 1.3
     * @see #getMinIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setMinIdle(int poolSize) {
        _minIdle = poolSize;
    }

    /**
     * Returns the minimum number of idle objects to maintain in each of the keyed
     * pools. This setting has no effect unless
     * <code>timeBetweenEvictionRunsMillis > 0</code> and attempts to ensure
     * that each pool has the required minimum number of instances are only
     * made during idle object eviction runs.
     * @return minimum size of the each keyed pool
     * @since Pool 1.3
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public int getMinIdle() {
        return _minIdle;
    }

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * before being returned by the {@link #borrowObject}
     * method.  If the object fails to validate,
     * it will be dropped from the pool, and we will attempt
     * to borrow another.
     *
     * @return <code>true</code> if objects are validated before being borrowed.
     * @see #setTestOnBorrow
     */
    public boolean getTestOnBorrow() {
        return _testOnBorrow;
    }

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * before being returned by the {@link #borrowObject}
     * method.  If the object fails to validate,
     * it will be dropped from the pool, and we will attempt
     * to borrow another.
     *
     * @param testOnBorrow whether object should be validated before being returned by borrowObject.
     * @see #getTestOnBorrow
     */
    public void setTestOnBorrow(boolean testOnBorrow) {
        _testOnBorrow = testOnBorrow;
    }

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * before being returned to the pool within the
     * {@link #returnObject}.
     *
     * @return <code>true</code> when objects will be validated before being returned.
     * @see #setTestOnReturn
     */
    public boolean getTestOnReturn() {
        return _testOnReturn;
    }

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * before being returned to the pool within the
     * {@link #returnObject}.
     *
     * @param testOnReturn <code>true</code> so objects will be validated before being returned.
     * @see #getTestOnReturn
     */
    public void setTestOnReturn(boolean testOnReturn) {
        _testOnReturn = testOnReturn;
    }

    /**
     * Returns the number of milliseconds to sleep between runs of the
     * idle object evictor thread.
     * When non-positive, no idle object evictor thread will be
     * run.
     *
     * @return milliseconds to sleep between evictor runs.
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public long getTimeBetweenEvictionRunsMillis() {
        return _timeBetweenEvictionRunsMillis;
    }

    /**
     * Sets the number of milliseconds to sleep between runs of the
     * idle object evictor thread.
     * When non-positive, no idle object evictor thread will be
     * run.
     *
     * @param timeBetweenEvictionRunsMillis milliseconds to sleep between evictor runs.
     * @see #getTimeBetweenEvictionRunsMillis
     */
    public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
        _timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        startEvictor(_timeBetweenEvictionRunsMillis);
    }

    /**
     * Returns the max number of objects to examine during each run of the
     * idle object evictor thread (if any).
     *
     * @return number of objects to examine each eviction run.
     * @see #setNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public int getNumTestsPerEvictionRun() {
        return _numTestsPerEvictionRun;
    }

    /**
     * Sets the max number of objects to examine during each run of the
     * idle object evictor thread (if any).
     * <p>
     * When a negative value is supplied, 
     * <code>ceil({@link #getNumIdle()})/abs({@link #getNumTestsPerEvictionRun})</code>
     * tests will be run.  I.e., when the value is <code>-n</code>, roughly one <code>n</code>th of the
     * idle objects will be tested per run.  When the value is positive, the number of tests
     * actually performed in each run will be the minimum of this value and the number of instances
     * idle in the pools.
     *
     * @param numTestsPerEvictionRun number of objects to examine each eviction run.
     * @see #setNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        _numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    /**
     * Returns the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor
     * (if any).
     *
     * @return minimum amount of time an object may sit idle in the pool before it is eligible for eviction.
     * @see #setMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public long getMinEvictableIdleTimeMillis() {
        return _minEvictableIdleTimeMillis;
    }

    /**
     * Sets the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor
     * (if any).
     * When non-positive, no objects will be evicted from the pool
     * due to idle time alone.
     *
     * @param minEvictableIdleTimeMillis minimum amount of time an object may sit idle in the pool before
     * it is eligible for eviction.
     * @see #getMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
        _minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
    }

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * by the idle object evictor (if any).  If an object
     * fails to validate, it will be dropped from the pool.
     *
     * @return <code>true</code> when objects are validated when borrowed.
     * @see #setTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public boolean getTestWhileIdle() {
        return _testWhileIdle;
    }

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * by the idle object evictor (if any).  If an object
     * fails to validate, it will be dropped from the pool.
     *
     * @param testWhileIdle <code>true</code> so objects are validated when borrowed.
     * @see #getTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setTestWhileIdle(boolean testWhileIdle) {
        _testWhileIdle = testWhileIdle;
    }

    /**
     * Sets the configuration.
     * @param conf the new configuration to use.
     * @see GenericKeyedObjectPool.Config
     */
    public void setConfig(GenericKeyedObjectPool.Config conf) {
        setMaxIdle(conf.maxIdle);
        setMaxActive(conf.maxActive);
        setMaxTotal(conf.maxTotal);
        setMinIdle(conf.minIdle);
        setMaxWait(conf.maxWait);
        setWhenExhaustedAction(conf.whenExhaustedAction);
        setTestOnBorrow(conf.testOnBorrow);
        setTestOnReturn(conf.testOnReturn);
        setTestWhileIdle(conf.testWhileIdle);
        setNumTestsPerEvictionRun(conf.numTestsPerEvictionRun);
        setMinEvictableIdleTimeMillis(conf.minEvictableIdleTimeMillis);
        setTimeBetweenEvictionRunsMillis(conf.timeBetweenEvictionRunsMillis);
    }

    /**
     * Whether or not the idle object pools act as LIFO queues. True means
     * that borrowObject returns the most recently used ("last in") idle object
     * in a pool (if there are idle instances available).  False means that
     * the pools behave as FIFO queues - objects are taken from idle object
     * pools in the order that they are returned.
     *
     * @return <code>true</code> if the pools are configured to act as LIFO queues
     * @since 1.4
     */
     public boolean getLifo() {
         return _lifo;
     }

     /**
      * Sets the LIFO property of the pools. True means that borrowObject returns
      * the most recently used ("last in") idle object in a pool (if there are
      * idle instances available).  False means that the pools behave as FIFO
      * queues - objects are taken from idle object pools in the order that
      * they are returned.
      *
      * @param lifo the new value for the lifo property
      * @since 1.4
      */
     public void setLifo(boolean lifo) {
         this._lifo = lifo;
     }

    //-- ObjectPool methods ------------------------------------------

    /**
     * <p>Borrows an object from the keyed pool associated with the given key.</p>
     * 
     * <p>If there is an idle instance available in the pool associated with the given key, then
     * either the most-recently returned (if {@link #getLifo() lifo} == true) or "oldest" (lifo == false)
     * instance sitting idle in the pool will be activated and returned.  If activation fails, or
     * {@link #getTestOnBorrow() testOnBorrow} is set to true and validation fails, the instance is destroyed and the
     * next available instance is examined.  This continues until either a valid instance is returned or there
     * are no more idle instances available.</p>
     * 
     * <p>If there are no idle instances available in the pool associated with the given key, behavior
     * depends on the {@link #getMaxActive() maxActive}, {@link #getMaxTotal() maxTotal}, and (if applicable)
     * {@link #getWhenExhaustedAction() whenExhaustedAction} and {@link #getMaxWait() maxWait} properties. If the
     * number of instances checked out from the pool under the given key is less than <code>maxActive</code> and
     * the total number of instances in circulation (under all keys) is less than <code>maxTotal</code>, a new instance
     * is created, activated and (if applicable) validated and returned to the caller.</p>
     * 
     * <p>If the associated keyed pool is exhausted (no available idle instances and no capacity to create new ones),
     * this method will either block ({@link #WHEN_EXHAUSTED_BLOCK}), throw a <code>NoSuchElementException</code>
     * ({@link #WHEN_EXHAUSTED_FAIL}), or grow ({@link #WHEN_EXHAUSTED_GROW} - ignoring maxActive, maxTotal properties).
     * The length of time that this method will block when <code>whenExhaustedAction == WHEN_EXHAUSTED_BLOCK</code>
     * is determined by the {@link #getMaxWait() maxWait} property.</p>
     * 
     * <p>When the pool is exhausted, multiple calling threads may be simultaneously blocked waiting for instances
     * to become available.  As of pool 1.5, a "fairness" algorithm has been implemented to ensure that threads receive
     * available instances in request arrival order.</p>
     * 
     * @param key pool key
     * @return object instance from the keyed pool
     * @throws NoSuchElementException if a keyed object instance cannot be returned.
     */
     @Override
    public T borrowObject(K key) throws Exception {

        assertOpen();

        PooledObject<T> p = null;

        // Get local copy of current config so it is consistent for entire
        // method execution
        WhenExhaustedAction whenExhaustedAction = _whenExhaustedAction;
        long maxWait = _maxWait;

        boolean create;
        ObjectDeque<T> objectDeque = poolMap.get(key);
        
        while (p == null) {
            create = false;
            if (whenExhaustedAction == WhenExhaustedAction.FAIL) {
                if (objectDeque != null) {
                    p = objectDeque.getIdleObjects().pollFirst();
                }
                if (p == null) {
                    create = true;
                    p = create(key, false);
                }
                if (p == null) {
                    throw new NoSuchElementException("Pool exhausted");
                }
                if (!p.allocate()) {
                    p = null;
                }
            } else if (whenExhaustedAction == WhenExhaustedAction.BLOCK) {
                if (objectDeque != null) {
                    p = objectDeque.getIdleObjects().pollFirst();
                }
                if (p == null) {
                    create = true;
                    p = create(key, false);
                }
                if (p == null && objectDeque != null) {
                    if (maxWait < 1) {
                        p = objectDeque.getIdleObjects().takeFirst();
                    } else {
                        p = objectDeque.getIdleObjects().pollFirst(maxWait,
                                TimeUnit.MILLISECONDS);
                    }
                }
                if (p == null) {
                    throw new NoSuchElementException(
                            "Timeout waiting for idle object");
                }
                if (!p.allocate()) {
                    p = null;
                }
            } else if (whenExhaustedAction == WhenExhaustedAction.GROW) {
                if (objectDeque != null) {
                    p = objectDeque.getIdleObjects().pollFirst();
                }
                if (p == null) {
                    create = true;
                    p = create(key, true);
                }
                if (!p.allocate()) {
                    p = null;
                }
            }

            if (p != null) {
                try {
                    _factory.activateObject(key, p.getObject());
                } catch (Exception e) {
                    try {
                        destroy(key, p);
                    } catch (Exception e1) {
                        // Ignore - activation failure is more important
                    }
                    p = null;
                    if (create) {
                        NoSuchElementException nsee = new NoSuchElementException(
                                "Unable to activate object");
                        nsee.initCause(e);
                        throw nsee;
                    }
                }
                if (p != null && getTestOnBorrow()) {
                    boolean validate = false;
                    Throwable validationThrowable = null;
                    try {
                        validate = _factory.validateObject(key, p.getObject());
                    } catch (Throwable t) {
                        PoolUtils.checkRethrow(t);
                    }
                    if (!validate) {
                        try {
                            destroy(key, p);
                        } catch (Exception e) {
                            // Ignore - validation failure is more important
                        }
                        p = null;
                        if (create) {
                            NoSuchElementException nsee = new NoSuchElementException(
                                    "Unable to validate object");
                            nsee.initCause(validationThrowable);
                            throw nsee;
                        }
                    }
                }
            }
        }

        return p.getObject();
    }


     /**
      * <p>Returns an object to a keyed pool.</p>
      * 
      * <p>For the pool to function correctly, the object instance <strong>must</strong> have been borrowed
      * from the pool (under the same key) and not yet returned. Repeated <code>returnObject</code> calls on
      * the same object/key pair (with no <code>borrowObject</code> calls in between) will result in multiple
      * references to the object in the idle instance pool.</p>
      * 
      * <p>If {@link #getMaxIdle() maxIdle} is set to a positive value and the number of idle instances under the given
      * key has reached this value, the returning instance is destroyed.</p>
      * 
      * <p>If {@link #getTestOnReturn() testOnReturn} == true, the returning instance is validated before being returned
      * to the idle instance pool under the given key.  In this case, if validation fails, the instance is destroyed.</p>
      * 
      * @param key pool key
      * @param obj instance to return to the keyed pool
      * @throws Exception
      */
     @Override
     public void returnObject(K key, T t) throws Exception {
         
         ObjectDeque<T> objectDeque = poolMap.get(key);
         
         PooledObject<T> p = objectDeque.getAllObjects().get(t);
         
         if (p == null) {
             throw new IllegalStateException(
                     "Returned object not currently part of this pool");
         }

         if (getTestOnReturn()) {
             if (!_factory.validateObject(key, t)) {
                 try {
                     destroy(key, p);
                 } catch (Exception e) {
                     // TODO - Ignore?
                 }
                 return;
             }
         }

         try {
             _factory.passivateObject(key, t);
         } catch (Exception e1) {
             try {
                 destroy(key, p);
             } catch (Exception e) {
                 // TODO - Ignore?
             }
             return;
         }

         if (!p.deallocate()) {
             // TODO - Should not happen;
         }

         int maxIdle = getMaxIdle();
         LinkedBlockingDeque<PooledObject<T>> idleObjects =
             objectDeque.getIdleObjects();

         if (isClosed() || maxIdle > -1 && maxIdle <= idleObjects.size()) {
             try {
                 destroy(key, p);
             } catch (Exception e) {
                 // TODO - Ignore?
             }
         } else {
             if (getLifo()) {
                 idleObjects.addFirst(p);
             } else {
                 idleObjects.addLast(p);
             }
         }
     }


     /**
      * {@inheritDoc}
      * <p>Activation of this method decrements the active count associated with the given keyed pool 
      * and attempts to destroy <code>obj.</code></p>
      * 
      * @param key pool key
      * @param obj instance to invalidate
      * @throws Exception if an exception occurs destroying the object
      */
     @Override
     public void invalidateObject(K key, T obj) throws Exception {
         
         ObjectDeque<T> objectDeque = poolMap.get(key);
         
         PooledObject<T> p = objectDeque.getAllObjects().get(obj);
         if (p == null) {
             throw new IllegalStateException(
                     "Object not currently part of this pool");
         }
         destroy(key, p);
     }


     /**
      * Clears any objects sitting idle in the pool by removing them from the
      * idle instance pool and then invoking the configured PoolableObjectFactory's
      * {@link KeyedPoolableObjectFactory#destroyObject(Object, Object)} method on
      * each idle instance.
      *  
      * <p> Implementation notes:
      * <ul><li>This method does not destroy or effect in any way instances that are
      * checked out when it is invoked.</li>
      * <li>Invoking this method does not prevent objects being
      * returned to the idle instance pool, even during its execution. It locks
      * the pool only during instance removal. Additional instances may be returned
      * while removed items are being destroyed.</li>
      * <li>Exceptions encountered destroying idle instances are swallowed.</li></ul></p>
      */
     @Override
     public void clear() {
         Iterator<K> iter = poolMap.keySet().iterator();
         
         while (iter.hasNext()) {
             clear(iter.next());
         }
     }


     /**
      * Clears the specified pool, removing all pooled instances corresponding to the given <code>key</code>.
      *
      * @param key the key to clear
      */
     @Override
     public void clear(K key) {
         
         ObjectDeque<T> objectDeque = poolMap.get(key);
         if (objectDeque == null) {
             return;
         }
         LinkedBlockingDeque<PooledObject<T>> idleObjects =
                 objectDeque.getIdleObjects();
         
         PooledObject<T> p = idleObjects.poll();

         while (p != null) {
             try {
                 destroy(key, p);
             } catch (Exception e) {
                 // TODO - Ignore?
             }
             p = idleObjects.poll();
         }
     }


     /**
      * Returns the total number of instances current borrowed from this pool but not yet returned.
      *
      * @return the total number of instances currently borrowed from this pool
      */
     @Override
     public int getNumActive() {
         return numTotal.get() - getNumIdle();
     }

     /**
      * Returns the total number of instances currently idle in this pool.
      *
      * @return the total number of instances currently idle in this pool
      */
     @Override
     public int getNumIdle() {
         Iterator<ObjectDeque<T>> iter = poolMap.values().iterator();
         int result = 0;
         
         while (iter.hasNext()) {
             result += iter.next().getIdleObjects().size();
         }

         return result;
     }

     /**
      * Returns the number of instances currently borrowed from but not yet returned
      * to the pool corresponding to the given <code>key</code>.
      *
      * @param key the key to query
      * @return the number of instances corresponding to the given <code>key</code> currently borrowed in this pool
      */
     @Override
     public int getNumActive(K key) {
         final ObjectDeque<T> objectDeque = poolMap.get(key);
         if (objectDeque != null) {
             return objectDeque.getNumActive().get() -
                     objectDeque.getIdleObjects().size();
         } else {
             return 0;
         }
     }

     /**
      * Returns the number of instances corresponding to the given <code>key</code> currently idle in this pool.
      *
      * @param key the key to query
      * @return the number of instances corresponding to the given <code>key</code> currently idle in this pool
      */
     @Override
     public synchronized int getNumIdle(K key) {
         final ObjectDeque<T> objectDeque = poolMap.get(key);
         return objectDeque != null ? objectDeque.getIdleObjects().size() : 0;
     }


     /**
      * <p>Closes the keyed object pool.  Once the pool is closed, {@link #borrowObject(Object)}
      * will fail with IllegalStateException, but {@link #returnObject(Object, Object)} and
      * {@link #invalidateObject(Object, Object)} will continue to work, with returned objects
      * destroyed on return.</p>
      * 
      * <p>Destroys idle instances in the pool by invoking {@link #clear()}.</p> 
      * 
      * @throws Exception
      */
     @Override
     public void close() throws Exception {
         super.close();
         clear();
         evictionIterator = null;
         evictionKeyIterator = null;
         startEvictor(-1L);
     }


     /**
      * <p>Sets the keyed poolable object factory associated with this pool.</p>
      * 
      * <p>If this method is called when objects are checked out of any of the keyed pools,
      * an IllegalStateException is thrown.  Calling this method also has the side effect of
      * destroying any idle instances in existing keyed pools, using the original factory.</p>
      * 
      * @param factory KeyedPoolableObjectFactory to use when creating keyed object pool instances
      * @throws IllegalStateException if there are active (checked out) instances associated with this keyed object pool
      * @deprecated to be removed in version 2.0
      */
     @Override
     @Deprecated
     public void setFactory(KeyedPoolableObjectFactory<K,T> factory) throws IllegalStateException {
         assertOpen();
         if (0 < getNumActive()) {
             throw new IllegalStateException("Objects are already active");
         }
         clear();
         _factory = factory;
     }

     
     /**
      * Clears oldest 15% of objects in pool.  The method sorts the
      * objects into a TreeMap and then iterates the first 15% for removal.
      * 
      * @since Pool 1.3
      */
     public void clearOldest() {

         // build sorted map of idle objects
         final Map<PooledObject<T>, K> map = new TreeMap<PooledObject<T>, K>();

         for (K k : poolMap.keySet()) {
             final LinkedBlockingDeque<PooledObject<T>> idleObjects =
                 poolMap.get(k).getIdleObjects();
             for (PooledObject<T> p : idleObjects) {
                 // each item into the map using the PooledObject object as the
                 // key. It then gets sorted based on the idle time
                 map.put(p, k);
             }
         }

         // Now iterate created map and kill the first 15% plus one to account
         // for zero
         int itemsToRemove = ((int) (map.size() * 0.15)) + 1;
         Iterator<Map.Entry<PooledObject<T>, K>> iter =
             map.entrySet().iterator();

         while (iter.hasNext() && itemsToRemove > 0) {
             Map.Entry<PooledObject<T>, K> entry = iter.next();
             // kind of backwards on naming.  In the map, each key is the
             // PooledObject because it has the ordering with the timestamp
             // value.  Each value that the key references is the key of the
             // list it belongs to.
             K key = entry.getValue();
             PooledObject<T> p = entry.getKey();
             try {
                destroy(key, p);
            } catch (Exception e) {
                // TODO - Ignore?
            }
             itemsToRemove--;
         }
     }


     /**
      * <p>Perform <code>numTests</code> idle object eviction tests, evicting
      * examined objects that meet the criteria for eviction. If
      * <code>testWhileIdle</code> is true, examined objects are validated
      * when visited (and removed if invalid); otherwise only objects that
      * have been idle for more than <code>minEvicableIdletimeMillis</code>
      * are removed.</p>
      *
      * <p>Successive activations of this method examine objects in keyed pools
      * in sequence, cycling through the keys and examining objects in
      * oldest-to-youngest order within the keyed pools.</p>
      *
      * @throws Exception when there is a problem evicting idle objects.
      */
     public void evict() throws Exception {
         assertOpen();

         if (getNumIdle() == 0) {
             return;
         }

         boolean testWhileIdle = _testWhileIdle;
         long idleEvictTime = Long.MAX_VALUE;
         
         if (getMinEvictableIdleTimeMillis() > 0) {
             idleEvictTime = getMinEvictableIdleTimeMillis();
         }

         PooledObject<T> underTest = null;
         LinkedBlockingDeque<PooledObject<T>> idleObjects = null;
         
         for (int i = 0, m = getNumTests(); i < m; i++) {
             if(evictionIterator == null || !evictionIterator.hasNext()) {
                 if (evictionKeyIterator == null ||
                         !evictionKeyIterator.hasNext()) {
                     List<K> keyCopy = new ArrayList<K>();
                     keyCopy.addAll(poolKeyList);
                     evictionKeyIterator = keyCopy.iterator();
                 }
                 while (evictionKeyIterator.hasNext()) {
                     evictionKey = evictionKeyIterator.next();
                     ObjectDeque<T> objectDeque = poolMap.get(evictionKey);
                     if (objectDeque == null) {
                         continue;
                     }
                     idleObjects = objectDeque.getIdleObjects();
                     
                     if (getLifo()) {
                         evictionIterator = idleObjects.descendingIterator();
                     } else {
                         evictionIterator = idleObjects.iterator();
                     }
                     if (evictionIterator.hasNext()) {
                         break;
                     }
                     evictionIterator = null;
                 }
             }
             if (evictionIterator == null) {
                 // Pools exhausted
                 return;
             }
             try {
                 underTest = evictionIterator.next();
             } catch (NoSuchElementException nsee) {
                 // Object was borrowed in another thread
                 // Don't count this as an eviction test so reduce i;
                 i--;
                 evictionIterator = null;
                 continue;
             }

             if (!underTest.startEvictionTest()) {
                 // Object was borrowed in another thread
                 // Don't count this as an eviction test so reduce i;
                 i--;
                 continue;
             }

             if (idleEvictTime < underTest.getIdleTimeMillis()) {
                 destroy(evictionKey, underTest);
             } else {
                 if (testWhileIdle) {
                     boolean active = false;
                     try {
                         _factory.activateObject(evictionKey, 
                                 underTest.getObject());
                         active = true;
                     } catch (Exception e) {
                         destroy(evictionKey, underTest);
                     }
                     if (active) {
                         if (!_factory.validateObject(evictionKey,
                                 underTest.getObject())) {
                             destroy(evictionKey, underTest);
                         } else {
                             try {
                                 _factory.passivateObject(evictionKey,
                                         underTest.getObject());
                             } catch (Exception e) {
                                 destroy(evictionKey, underTest);
                             }
                         }
                     }
                 }
                 if (!underTest.endEvictionTest(idleObjects)) {
                     // TODO - May need to add code here once additional states
                     // are used
                 }
             }
         }
     }

     
    /**
     * TODO: Remove the force parameters along with support for when exhausted
     * grow.
     */
    private PooledObject<T> create(K key, boolean force) throws Exception {
        int maxActive = getMaxActive(); // Per key
        int maxTotal = getMaxTotal();   // All keys

        // Check against the overall limit
        boolean loop = true;
        
        while (loop) {
            int newNumTotal = numTotal.incrementAndGet();
            if (!force && maxTotal > -1 && newNumTotal > maxTotal) {
                numTotal.decrementAndGet();
                if (getNumIdle() == 0) {
                    return null;
                } else {
                    clearOldest();
                }
            } else {
                loop = false;
            }
        }
         
        // Make sure the key exists in the poolMap
        ObjectDeque<T> objectDeque;
        int newNumActive;
        synchronized (poolMap) {
            // This all has to be in the sync block to ensure that the key is
            // not removed by destroy
            objectDeque = poolMap.get(key);
            if (objectDeque == null) {
                objectDeque = new ObjectDeque<T>();
                newNumActive = objectDeque.getNumActive().incrementAndGet();
                poolMap.put(key, objectDeque);
                poolKeyList.add(key);
            } else {
                newNumActive = objectDeque.getNumActive().incrementAndGet();
            }
        }

        // Check against the per key limit
        if (!force && maxActive > -1 && newNumActive > maxActive) {
            cleanObjectDeque(key, objectDeque);
            numTotal.decrementAndGet();
            return null;
        }
         

        T t = null;
        try {
            t = _factory.makeObject(key);
        } catch (Exception e) {
            cleanObjectDeque(key, objectDeque);
            numTotal.decrementAndGet();
            throw e;
        }

        PooledObject<T> p = new PooledObject<T>(t);
        objectDeque.getAllObjects().put(t, p);
        return p;
    }

    private void destroy(K key, PooledObject<T> toDestory) throws Exception {
         
        ObjectDeque<T> objectDeque = poolMap.get(key);
        objectDeque.getIdleObjects().remove(toDestory);
        objectDeque.getAllObjects().remove(toDestory.getObject());

        try {
            _factory.destroyObject(key, toDestory.getObject());
        } finally {
            cleanObjectDeque(key, objectDeque);
            numTotal.decrementAndGet();
        }
    }

    private void cleanObjectDeque(K key, ObjectDeque<T> objectDeque) {
        int newNumActive = objectDeque.getNumActive().decrementAndGet();
        if (newNumActive == 0) {
            synchronized (poolMap) {
                newNumActive = objectDeque.getNumActive().get();
                if (newNumActive == 0) {
                    poolMap.remove(key);
                    poolKeyList.remove(key);
                }
            }
        }
    }

    
    /**
     * Iterates through all the known keys and creates any necessary objects to maintain
     * the minimum level of pooled objects.
     * @see #getMinIdle
     * @see #setMinIdle
     * @throws Exception If there was an error whilst creating the pooled objects.
     */
    private void ensureMinIdle() throws Exception {
        int minIdle = getMinIdle();
        if (minIdle < 1) {
            return;
        }

        for (K k : poolMap.keySet()) {
            ensureMinIdle(k);
        }
    }


    /**
     * Re-creates any needed objects to maintain the minimum levels of
     * pooled objects for the specified key.
     *
     * This method uses {@link #calculateDeficit} to calculate the number
     * of objects to be created. {@link #calculateDeficit} can be overridden to
     * provide a different method of calculating the number of objects to be
     * created.
     * @param key The key to process
     * @throws Exception If there was an error whilst creating the pooled objects
     */
    private void ensureMinIdle(K key) throws Exception {
        int minIdle = getMinIdle();
        if (minIdle < 1) {
            return;
        }

        // Calculate current pool objects
        ObjectDeque<T> objectDeque = poolMap.get(key);

        // this method isn't synchronized so the
        // calculateDeficit is done at the beginning
        // as a loop limit and a second time inside the loop
        // to stop when another thread already returned the
        // needed objects
        int deficit = calculateDeficit(objectDeque);

        for (int i = 0; i < deficit && calculateDeficit(objectDeque) > 0; i++) {
            addObject(key);
        }
    }

    
    /**
     * <p>Adds an object to the keyed pool.</p>
     * 
     * <p>Validates the object if testOnReturn == true and passivates it before returning it to the pool.
     * if validation or passivation fails, or maxIdle is set and there is no room in the pool, the instance
     * is destroyed.</p>
     * 
     * <p>Calls {@link #allocate()} on successful completion</p>
     * 
     * @param key pool key
     * @param obj instance to add to the keyed pool
     * @param decrementNumActive whether or not to decrement the active count associated with the keyed pool
     * @throws Exception
     */
    private void addIdleObject(K key, PooledObject<T> p) throws Exception {

        if (p != null) {
            _factory.passivateObject(key, p.getObject());
            LinkedBlockingDeque<PooledObject<T>> idleObjects =
                    poolMap.get(key).getIdleObjects();
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }
    }

    /**
     * Create an object using the {@link KeyedPoolableObjectFactory#makeObject factory},
     * passivate it, and then place it in the idle object pool.
     * <code>addObject</code> is useful for "pre-loading" a pool with idle objects.
     *
     * @param key the key a new instance should be added to
     * @throws Exception when {@link KeyedPoolableObjectFactory#makeObject} fails.
     * @throws IllegalStateException when no {@link #setFactory factory} has been set or after {@link #close} has been
     * called on this pool.
     */
    @Override
    public void addObject(K key) throws Exception {
        assertOpen();
        if (_factory == null) {
            throw new IllegalStateException("Cannot add objects without a factory.");
        }
        PooledObject<T> p = create(key, false);
        addIdleObject(key, p);
    }

    /**
     * Registers a key for pool control and ensures that {@link #getMinIdle()}
     * idle instances are created.
     *
     * @param key - The key to register for pool control.
     * @since Pool 1.3
     */
    public void preparePool(K key) throws Exception {
        ensureMinIdle(key);
    }

    //--- non-public methods ----------------------------------------

    /**
     * Start the eviction thread or service, or when
     * <code>delay</code> is non-positive, stop it
     * if it is already running.
     *
     * @param delay milliseconds between evictor runs.
     */
    protected synchronized void startEvictor(long delay) {
        if (null != _evictor) {
            EvictionTimer.cancel(_evictor);
            _evictor = null;
        }
        if (delay > 0) {
            _evictor = new Evictor();
            EvictionTimer.schedule(_evictor, delay, delay);
        }
    }

    /**
     * Returns pool info including {@link #getNumActive()}, {@link #getNumIdle()}
     * and currently defined keys.
     * 
     * @return string containing debug information
     */
    synchronized String debugInfo() {
        StringBuilder buf = new StringBuilder();
        buf.append("Active: ").append(getNumActive()).append("\n");
        buf.append("Idle: ").append(getNumIdle()).append("\n");
        for (Entry<K,ObjectDeque<T>> entry : poolMap.entrySet()) {
            buf.append(entry.getKey());
            buf.append(": ");
            buf.append(entry.getValue());
            buf.append("\n");
        }
        return buf.toString();
    }

    /** 
     * Returns the number of tests to be performed in an Evictor run,
     * based on the current values of <code>_numTestsPerEvictionRun</code>
     * and <code>_totalIdle</code>.
     * 
     * @see #setNumTestsPerEvictionRun
     * @return the number of tests for the Evictor to run
     */
    private synchronized int getNumTests() {
        int totalIdle = getNumIdle();
        if (_numTestsPerEvictionRun >= 0) {
            return Math.min(_numTestsPerEvictionRun, totalIdle);
        }
        return(int)(Math.ceil(totalIdle/Math.abs((double)_numTestsPerEvictionRun)));
    }

    /**
     * This returns the number of objects to create during the pool
     * sustain cycle. This will ensure that the minimum number of idle
     * instances is maintained without going past the maxActive value.
     * 
     * @param pool the ObjectPool to calculate the deficit for
     * @return The number of objects to be created
     */
    private synchronized int calculateDeficit(ObjectDeque<T> objectDeque) {
        
        if (objectDeque == null) {
            return getMinIdle();
        }
        int objectDefecit = 0;
        
        //Calculate no of objects needed to be created, in order to have
        //the number of pooled objects < maxActive();
        objectDefecit = getMinIdle() - objectDeque.getIdleObjects().size();
        if (getMaxActive() > 0) {
            int growLimit = Math.max(0,
                    getMaxActive() - objectDeque.getIdleObjects().size());
            objectDefecit = Math.min(objectDefecit, growLimit);
        }

        // Take the maxTotal limit into account
        if (getMaxTotal() > 0) {
            int growLimit = Math.max(0, getMaxTotal() - getNumActive() - getNumIdle());
            objectDefecit = Math.min(objectDefecit, growLimit);
        }

        return objectDefecit;
    }

    //--- inner classes ----------------------------------------------

    /**
     * Maintains information on the per key queue for a given key.
     */
    private class ObjectDeque<S> {
        private final LinkedBlockingDeque<PooledObject<S>> idleObjects =
                new LinkedBlockingDeque<PooledObject<S>>();

        private AtomicInteger numActive = new AtomicInteger(0);

        private Map<S, PooledObject<S>> allObjects =
                new ConcurrentHashMap<S, PooledObject<S>>();

        public LinkedBlockingDeque<PooledObject<S>> getIdleObjects() {
            return idleObjects;
        }
        
        public AtomicInteger getNumActive() {
            return numActive;
        }
        
        public Map<S, PooledObject<S>> getAllObjects() {
            return allObjects;
        }
    }

    /**
     * The idle object evictor {@link TimerTask}.
     * @see GenericKeyedObjectPool#setTimeBetweenEvictionRunsMillis
     */
    private class Evictor extends TimerTask {
        /**
         * Run pool maintenance.  Evict objects qualifying for eviction and then
         * invoke {@link GenericKeyedObjectPool#ensureMinIdle()}.
         */
        @Override
        public void run() {
            //Evict from the pool
            try {
                evict();
            } catch(Exception e) {
                // ignored
            } catch(OutOfMemoryError oome) {
                // Log problem but give evictor thread a chance to continue in
                // case error is recoverable
                oome.printStackTrace(System.err);
            }
            //Re-create idle instances.
            try {
                ensureMinIdle();
            } catch (Exception e) {
                // ignored
            }
        }
    }

    /**
     * A simple "struct" encapsulating the
     * configuration information for a <code>GenericKeyedObjectPool</code>.
     * @see GenericKeyedObjectPool#GenericKeyedObjectPool(KeyedPoolableObjectFactory,GenericKeyedObjectPool.Config)
     * @see GenericKeyedObjectPool#setConfig
     */
    public static class Config {
        //CHECKSTYLE: stop VisibilityModifier
        /**
         * @see GenericKeyedObjectPool#setMaxIdle
         */
        public int maxIdle = GenericKeyedObjectPool.DEFAULT_MAX_IDLE;
        /**
         * @see GenericKeyedObjectPool#setMaxActive
         */
        public int maxActive = GenericKeyedObjectPool.DEFAULT_MAX_ACTIVE;
        /**
         * @see GenericKeyedObjectPool#setMaxTotal
         */
        public int maxTotal = GenericKeyedObjectPool.DEFAULT_MAX_TOTAL;
        /**
         * @see GenericKeyedObjectPool#setMinIdle
         */
        public int minIdle = GenericKeyedObjectPool.DEFAULT_MIN_IDLE;
        /**
         * @see GenericKeyedObjectPool#setMaxWait
         */
        public long maxWait = GenericKeyedObjectPool.DEFAULT_MAX_WAIT;
        /**
         * @see GenericKeyedObjectPool#setWhenExhaustedAction
         */
        public WhenExhaustedAction whenExhaustedAction = GenericKeyedObjectPool.DEFAULT_WHEN_EXHAUSTED_ACTION;
        /**
         * @see GenericKeyedObjectPool#setTestOnBorrow
         */
        public boolean testOnBorrow = GenericKeyedObjectPool.DEFAULT_TEST_ON_BORROW;
        /**
         * @see GenericKeyedObjectPool#setTestOnReturn
         */
        public boolean testOnReturn = GenericKeyedObjectPool.DEFAULT_TEST_ON_RETURN;
        /**
         * @see GenericKeyedObjectPool#setTestWhileIdle
         */
        public boolean testWhileIdle = GenericKeyedObjectPool.DEFAULT_TEST_WHILE_IDLE;
        /**
         * @see GenericKeyedObjectPool#setTimeBetweenEvictionRunsMillis
         */
        public long timeBetweenEvictionRunsMillis = GenericKeyedObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;
        /**
         * @see GenericKeyedObjectPool#setNumTestsPerEvictionRun
         */
        public int numTestsPerEvictionRun =  GenericKeyedObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN;
        /**
         * @see GenericKeyedObjectPool#setMinEvictableIdleTimeMillis
         */
        public long minEvictableIdleTimeMillis = GenericKeyedObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
        /**
         * @see GenericKeyedObjectPool#setLifo
         */
        public boolean lifo = GenericKeyedObjectPool.DEFAULT_LIFO;
        //CHECKSTYLE: resume VisibilityModifier
    }


    //--- protected attributes ---------------------------------------

    /**
     * The cap on the number of idle instances in the pool.
     * @see #setMaxIdle
     * @see #getMaxIdle
     */
    private int _maxIdle = DEFAULT_MAX_IDLE;

    /**
     * The minimum no of idle objects to keep in the pool.
     * @see #setMinIdle
     * @see #getMinIdle
     */
    private volatile int _minIdle = DEFAULT_MIN_IDLE;

    /**
     * The cap on the number of active instances from the pool.
     * @see #setMaxActive
     * @see #getMaxActive
     */
    private int _maxActive = DEFAULT_MAX_ACTIVE;

    /**
     * The cap on the total number of instances from the pool if non-positive.
     * @see #setMaxTotal
     * @see #getMaxTotal
     */
    private int _maxTotal = DEFAULT_MAX_TOTAL;

    /**
     * The maximum amount of time (in millis) the
     * {@link #borrowObject} method should block before throwing
     * an exception when the pool is exhausted and the
     * {@link #getWhenExhaustedAction "when exhausted" action} is
     * {@link #WHEN_EXHAUSTED_BLOCK}.
     *
     * When less than or equal to 0, the {@link #borrowObject} method
     * may block indefinitely.
     *
     * @see #setMaxWait
     * @see #getMaxWait
     * @see #WHEN_EXHAUSTED_BLOCK
     * @see #setWhenExhaustedAction
     * @see #getWhenExhaustedAction
     */
    private long _maxWait = DEFAULT_MAX_WAIT;

    /**
     * The action to take when the {@link #borrowObject} method
     * is invoked when the pool is exhausted (the maximum number
     * of "active" objects has been reached).
     *
     * @see #DEFAULT_WHEN_EXHAUSTED_ACTION
     * @see #setWhenExhaustedAction
     * @see #getWhenExhaustedAction
     */
    private WhenExhaustedAction _whenExhaustedAction = DEFAULT_WHEN_EXHAUSTED_ACTION;

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * before being returned by the {@link #borrowObject}
     * method.  If the object fails to validate,
     * it will be dropped from the pool, and we will attempt
     * to borrow another.
     *
     * @see #setTestOnBorrow
     * @see #getTestOnBorrow
     */
    private volatile boolean _testOnBorrow = DEFAULT_TEST_ON_BORROW;

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * before being returned to the pool within the
     * {@link #returnObject}.
     *
     * @see #getTestOnReturn
     * @see #setTestOnReturn
     */
    private volatile boolean _testOnReturn = DEFAULT_TEST_ON_RETURN;

    /**
     * When <code>true</code>, objects will be
     * {@link org.apache.commons.pool2.PoolableObjectFactory#validateObject validated}
     * by the idle object evictor (if any).  If an object
     * fails to validate, it will be dropped from the pool.
     *
     * @see #setTestWhileIdle
     * @see #getTestWhileIdle
     * @see #getTimeBetweenEvictionRunsMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    private boolean _testWhileIdle = DEFAULT_TEST_WHILE_IDLE;

    /**
     * The number of milliseconds to sleep between runs of the
     * idle object evictor thread.
     * When non-positive, no idle object evictor thread will be
     * run.
     *
     * @see #setTimeBetweenEvictionRunsMillis
     * @see #getTimeBetweenEvictionRunsMillis
     */
    private long _timeBetweenEvictionRunsMillis = DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;

    /**
     * The number of objects to examine during each run of the
     * idle object evictor thread (if any).
     * <p>
     * When a negative value is supplied, <code>ceil({@link #getNumIdle})/abs({@link #getNumTestsPerEvictionRun})</code>
     * tests will be run.  I.e., when the value is <code>-n</code>, roughly one <code>n</code>th of the
     * idle objects will be tested per run.
     *
     * @see #setNumTestsPerEvictionRun
     * @see #getNumTestsPerEvictionRun
     * @see #getTimeBetweenEvictionRunsMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    private int _numTestsPerEvictionRun =  DEFAULT_NUM_TESTS_PER_EVICTION_RUN;

    /**
     * The minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor
     * (if any).
     * When non-positive, no objects will be evicted from the pool
     * due to idle time alone.
     *
     * @see #setMinEvictableIdleTimeMillis
     * @see #getMinEvictableIdleTimeMillis
     * @see #getTimeBetweenEvictionRunsMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    private long _minEvictableIdleTimeMillis = DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS;

    /** My {@link KeyedPoolableObjectFactory}. */
    private KeyedPoolableObjectFactory<K,T> _factory = null;

    /**
     * My idle object eviction {@link TimerTask}, if any.
     */
    private Evictor _evictor = null;

    /** Whether or not the pools behave as LIFO queues (last in first out) */
    private boolean _lifo = DEFAULT_LIFO;

    /** My hash of pools (ObjectQueue). */
    private Map<K,ObjectDeque<T>> poolMap =
            new ConcurrentHashMap<K,ObjectDeque<T>>();
    
    /** List of pool keys - used to control eviction order */
    private List<K> poolKeyList = new ArrayList<K>();

    /**
     * The combined count of the currently active objects for all keys and those
     * in the process of being created. Under load, it may exceed
     * {@link #_maxTotal} but there will never be more than {@link #_maxTotal}
     * created at any one time.
     */
    private AtomicInteger numTotal = new AtomicInteger(0);
    
    /**
     * An iterator for {@link ObjectDeque#getIdleObjects()} that is used by the
     * evictor.
     */
    private Iterator<PooledObject<T>> evictionIterator = null;

    /**
     * An iterator for {@link #poolMap} entries.
     */
    private Iterator<K> evictionKeyIterator = null;
    
    /**
     * The key associated with the {@link ObjectDeque#getIdleObjects()}
     * currently being evicted.
     */
    private K evictionKey = null;
}