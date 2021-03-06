/*
 * Copyright 2012-2015, the original author or authors.
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

package com.flipkart.phantom.thrift.impl;

import java.lang.reflect.Method;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.pool.impl.GenericObjectPool;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.transport.TSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

import com.flipkart.phantom.task.spi.AbstractHandler;
import com.flipkart.phantom.task.spi.TaskContext;
import com.flipkart.phantom.thrift.impl.proxy.SocketObjectFactory;

/**
 * <code>ThriftProxy</code> holds the details of a ThriftProxy and loads the necessary Thrift Classes.
 * Note that this class works only with Thrift classes generated using the IDL compiler version 0.9. This is because it uses reflection to determine
 * declared methods on the interface. The target service may be of any version. This implementation has been tested with Thrift versions 0.6 and 0.2.
 * 
 * @author Regunath B
 * @version 1.0, 28 March, 2013
 */
public abstract class ThriftProxy extends AbstractHandler implements InitializingBean {

	/** The default Thrift interface class name for Thrift services */
	private static final String DEFAULT_SERVICE_INTERFACE_NAME="Iface";
	
	/** The default Thrift TProcessor class name */
	private static final String DEFAULT_PROCESSOR_CLASS_NAME="Processor";
	
	/** Logger for this class*/
	private static final Logger LOGGER = LoggerFactory.getLogger(ThriftProxy.class);
	
	/** The target Thrift server connect details*/
	private String thriftServer;
	private int thriftPort;
	private int thriftTimeoutMillis = -1;
	
	/** The fully qualified class name of the Thrift service generated by the Thrift compiler from the IDL file*/
	private String thriftServiceClass;

	/** Map of the method names and the respective Thrift ProcessFunction instances*/
	@SuppressWarnings("rawtypes")
	protected Map<String, ProcessFunction> processMap = new HashMap<String, ProcessFunction>();
	
    /** Properties for initializing Generic Object Pool */
    private int poolSize =10;
    private long maxWait = 100;
    private int maxIdle = poolSize;
    private int minIdle = poolSize/2;
    private long timeBetweenEvictionRunsMillis = 20000;

    /** The GenericObjectPool object */
    private GenericObjectPool<Socket> socketPool;
	
	/**
	 * Interface method implementation. Checks if all mandatory properties have been set
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() throws Exception {		
		Assert.notNull(this.thriftServer, "The 'thriftServer' may not be null");	
		Assert.notNull(this.thriftServiceClass, "The 'thriftServiceClass' may not be null");	
	}

	/**
	 * Initialize this ThriftProxy
	 */
	public void init(TaskContext context) throws Exception {
		if(this.thriftServiceClass == null) {
			throw new AssertionError("The 'thriftServiceClass' may not be null");
		}
		if(this.processMap==null || this.processMap.isEmpty()) {
			throw new AssertionError("ProcessFunctions not populated. Maybe The 'thriftServiceClass' is not a valid class?");
		}
		if (this.thriftTimeoutMillis == -1) { // implying none set
			throw new Exception("'thriftTimeoutMillis' must be set to a non-negative value!");
		}

        //Create pool
        this.socketPool = new GenericObjectPool<Socket>(
                new SocketObjectFactory(this),
                this.poolSize,
                GenericObjectPool.WHEN_EXHAUSTED_GROW,
                this.maxWait ,
                this.maxIdle ,
                this.minIdle , false, false,
                this.timeBetweenEvictionRunsMillis,
                GenericObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                GenericObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                true);
	}

	/**
	 * Gets a pooled TSocket instance
	 * @return a TSocket instance
	 */
    public TSocket getPooledSocket() {
        try {
            return new TSocket(this.socketPool.borrowObject());
        } catch (Exception e) {
            LOGGER.error("Error while borrowing TSocket : " + e.getMessage(),e);
            throw new RuntimeException("Error while borrowing TSocket : " + e.getMessage(),e);
        }
    }
    
    /**
     * Returns the specified TSocket back to the pool
     * @param socket the pooled TSocket instance
     * @param isConnectionValid flag to indicate if the socket was found to be invalid during use
     */
    public void returnPooledSocket(TSocket socket, boolean isConnectionValid) {
        try {
        	if (isConnectionValid) {
        		this.socketPool.returnObject(socket.getSocket());
        	} else {
        		this.socketPool.invalidateObject(socket.getSocket());
        	}
        } catch (Exception e) {
            LOGGER.error("Error while returning TSocket : " + e.getMessage(),e);
            throw new RuntimeException("Error while borrowing TSocket : " + e.getMessage(),e);
        }
    }
	
	/**
	 * Get the name of this ThriftProxy.
	 * @return the name of this ThriftProxy
	 */
	public String getName() {
		return this.thriftServiceClass;
	}

    /**
     * Abstract method implementation
     * @see com.flipkart.phantom.task.spi.AbstractHandler#getType()
     */
    public String getType() {
        return "ThriftProxy";
    }

	/**
	 * Shutdown hooks provided by the ThriftProxy
	 */
	public void shutdown(TaskContext context) throws Exception {
		super.deactivate();
	}
	
	/** Getter/Setter methods */
	public String getThriftServer() {
		return thriftServer;
	}
	public void setThriftServer(String thriftServer) {
		this.thriftServer = thriftServer;
	}
	public int getThriftPort() {
		return thriftPort;
	}
	public void setThriftPort(int thriftPort) {
		this.thriftPort = thriftPort;
	}
	public int getThriftTimeoutMillis() {
		return thriftTimeoutMillis;
	}
	public void setThriftTimeoutMillis(int thriftTimeoutMillis) {
		this.thriftTimeoutMillis = thriftTimeoutMillis;
	}	
	@SuppressWarnings("rawtypes")
	public void setThriftServiceClass(String thriftServiceClass) {
		this.thriftServiceClass = thriftServiceClass;
		// Inspect and add ProcessFunction instances for all public methods on the declared service interface
		String serviceInterfaceClass = this.thriftServiceClass + "$" + DEFAULT_SERVICE_INTERFACE_NAME;
		try {
			Class serviceClass = Class.forName(serviceInterfaceClass);
			Method[] methods = serviceClass.getDeclaredMethods();
			for (Method method : methods) {
				String processFunctionClass = this.thriftServiceClass + "$" + DEFAULT_PROCESSOR_CLASS_NAME + "$" + method.getName();
				this.processMap.put(method.getName(), (ProcessFunction)Class.forName(processFunctionClass).newInstance());
			}
		} catch (Exception e) {
			LOGGER.error("Unable to inspect specified Thrift service class. Error is : " + e.getMessage(), e);
			// empty the processMap. This will fail the init of this handler in #afterPropertiesSet()
			this.processMap.clear();
		}		
	}
	public String getThriftServiceClass() {
		return thriftServiceClass;
	}
	public Map<String, ProcessFunction> getProcessMap() {
		return processMap;
	}
    public int getPoolSize() {
        return poolSize;
    }
    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
    public long getMaxWait() {
        return maxWait;
    }
    public void setMaxWait(long maxWait) {
        this.maxWait = maxWait;
    }
    public int getMaxIdle() {
        return maxIdle;
    }
    public void setMaxIdle(int maxIdle) {
        this.maxIdle = maxIdle;
    }
    public int getMinIdle() {
        return minIdle;
    }
    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }
    public long getTimeBetweenEvictionRunsMillis() {
        return timeBetweenEvictionRunsMillis;
    }
    public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
        this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
    }
	
	/** End Getter/Setter methods */

}