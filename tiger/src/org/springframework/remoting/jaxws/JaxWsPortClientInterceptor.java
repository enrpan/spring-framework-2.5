/*
 * Copyright 2002-2007 the original author or authors.
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

package org.springframework.remoting.jaxws;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.xml.namespace.QName;
import javax.xml.ws.ProtocolException;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.soap.SOAPFaultException;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.RemoteConnectFailureException;
import org.springframework.remoting.RemoteProxyFailureException;

/**
 * {@link org.aopalliance.intercept.MethodInterceptor}  for accessing a
 * specific port of a JAX-WS service.
 *
 * <p>Uses either {@link LocalJaxWsServiceFactory}'s facilities underneath,
 * or takes an explicit reference to an existing JAX-WS Service instance
 * (e.g. obtained via {@link org.springframework.jndi.JndiObjectFactoryBean}).
 *
 * @author Juergen Hoeller
 * @since 2.5
 * @see #setPortName
 * @see #setServiceInterface
 * @see javax.xml.ws.Service#getPort
 * @see org.springframework.remoting.RemoteAccessException
 * @see org.springframework.jndi.JndiObjectFactoryBean
 */
public class JaxWsPortClientInterceptor extends LocalJaxWsServiceFactory
		implements MethodInterceptor, InitializingBean {

	private Service jaxWsService;

	private String portName;

	private Class<?> serviceInterface;

	private boolean lookupServiceOnStartup = true;

	private QName portQName;

	private Object portStub;

	private final Object preparationMonitor = new Object();


	/**
	 * Set a reference to an existing JAX-WS Service instance,
	 * for example obtained via {@link org.springframework.jndi.JndiObjectFactoryBean}.
	 * If not set, {@link LocalJaxWsServiceFactory}'s properties have to be specified.
	 * @see #setWsdlDocumentUrl
	 * @see #setNamespaceUri
	 * @see #setServiceName
	 * @see org.springframework.jndi.JndiObjectFactoryBean
	 */
	public void setJaxWsService(Service jaxWsService) {
		this.jaxWsService = jaxWsService;
	}

	/**
	 * Return a reference to an existing JAX-WS Service instance, if any.
	 */
	public Service getJaxWsService() {
		return this.jaxWsService;
	}

	/**
	 * Set the name of the port.
	 * Corresponds to the "wsdl:port" name.
	 */
	public void setPortName(String portName) {
		this.portName = portName;
	}

	/**
	 * Return the name of the port.
	 */
	public String getPortName() {
		return this.portName;
	}

	/**
	 * Set the interface of the service that this factory should create a proxy for.
	 */
	public void setServiceInterface(Class serviceInterface) {
		if (serviceInterface != null && !serviceInterface.isInterface()) {
			throw new IllegalArgumentException("'serviceInterface' must be an interface");
		}
		this.serviceInterface = serviceInterface;
	}

	/**
	 * Return the interface of the service that this factory should create a proxy for.
	 */
	public Class getServiceInterface() {
		return this.serviceInterface;
	}

	/**
	 * Set whether to look up the JAX-WS service on startup.
	 * <p>Default is "true". Turn this flag off to allow for late start
	 * of the target server. In this case, the JAX-WS service will be
	 * lazily fetched on first access.
	 */
	public void setLookupServiceOnStartup(boolean lookupServiceOnStartup) {
		this.lookupServiceOnStartup = lookupServiceOnStartup;
	}


	public void afterPropertiesSet() {
		if (this.lookupServiceOnStartup) {
			prepare();
		}
	}

	public void prepare() {
		if (getServiceInterface() == null) {
			throw new IllegalArgumentException("Property 'serviceInterface' is required");
		}
		Service serviceToUse = getJaxWsService();
		if (serviceToUse == null) {
			serviceToUse = createJaxWsService();
		}
		this.portQName = getQName(getPortName() != null ? getPortName() : getServiceInterface().getName());
		this.portStub = (getPortName() != null ?
				serviceToUse.getPort(this.portQName, getServiceInterface()) : serviceToUse.getPort(getServiceInterface()));
	}

	/**
	 * Return whether this client interceptor has already been prepared,
	 * i.e. has already looked up the JAX-WS service and port.
	 */
	protected boolean isPrepared() {
		synchronized (this.preparationMonitor) {
			return (this.portStub != null);
		}
	}

	/**
	 * Return the prepared QName for the port.
	 * @see #setPortName
	 * @see #getQName
	 */
	protected final QName getPortQName() {
		return this.portQName;
	}

	/**
	 * Return the underlying JAX-WS port stub that this interceptor delegates to
	 * for each method invocation on the proxy.
	 */
	protected Object getPortStub() {
		return this.portStub;
	}


	public Object invoke(MethodInvocation invocation) throws Throwable {
		if (AopUtils.isToStringMethod(invocation.getMethod())) {
			return "JAX-WS proxy for port [" + getPortName() + "] of service [" + getServiceName() + "]";
		}
		// Lazily prepare service and stub if necessary.
		synchronized (this.preparationMonitor) {
			if (!isPrepared()) {
				prepare();
			}
		}
		return doInvoke(invocation);
	}

	/**
	 * Perform a JAX-WS service invocation based on the given method invocation.
	 * @param invocation the AOP method invocation
	 * @return the invocation result, if any
	 * @throws Throwable in case of invocation failure
	 * @see #getPortStub()
	 * @see #doInvoke(org.aopalliance.intercept.MethodInvocation, Object)
	 */
	protected Object doInvoke(MethodInvocation invocation) throws Throwable {
		try {
			return doInvoke(invocation, getPortStub());
		}
		catch (SOAPFaultException ex) {
			throw new JaxWsSoapFaultException(ex);
		}
		catch (ProtocolException ex) {
			return new RemoteConnectFailureException("Could not connect to remote service [" + this.portQName + "]", ex);
		}
		catch (WebServiceException ex) {
			throw new RemoteAccessException("Could not access remote service at [" + this.portQName + "]", ex);
		}
	}

	/**
	 * Perform a JAX-WS service invocation on the given port stub.
	 * @param invocation the AOP method invocation
	 * @param portStub the RMI port stub to invoke
	 * @return the invocation result, if any
	 * @throws Throwable in case of invocation failure
	 * @see #getPortStub()
	 */
	protected Object doInvoke(MethodInvocation invocation, Object portStub) throws Throwable {
		Method method = invocation.getMethod();
		try {
			return method.invoke(portStub, invocation.getArguments());
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
		catch (Throwable ex) {
			throw new RemoteProxyFailureException("Invocation of stub method failed: " + method, ex);
		}
	}

}
