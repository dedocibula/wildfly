/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.naming.service;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;

import javax.management.MBeanServer;
import javax.naming.Context;
import javax.naming.Reference;

import org.jboss.as.controller.Cancellable;
import org.jboss.as.controller.ModelAddOperationHandler;
import org.jboss.as.controller.NewOperationContext;
import org.jboss.as.controller.ResultHandler;
import org.jboss.as.naming.InitialContextFactoryService;
import org.jboss.as.naming.NamingContext;
import org.jboss.as.naming.context.NamespaceObjectFactory;
import org.jboss.as.server.NewRuntimeOperationContext;
import org.jboss.as.server.RuntimeOperationHandler;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.value.Values;

/**
 * @author Emanuel Muckenhuber
 */
public class NewNamingSubsystemAdd implements ModelAddOperationHandler, RuntimeOperationHandler {

    static final NewNamingSubsystemAdd INSTANCE = new NewNamingSubsystemAdd();

    /** {@inheritDoc} */
    public Cancellable execute(final NewOperationContext context, final ModelNode operation, final ResultHandler resultHandler) {

        final ModelNode compensatingOperation = new ModelNode();
        compensatingOperation.get(OP).set(REMOVE);
        compensatingOperation.get(OP_ADDR).set(operation.require(OP_ADDR));

        if(context instanceof NewRuntimeOperationContext) {
            final NewRuntimeOperationContext updateContext = (NewRuntimeOperationContext) context;
            NamingContext.initializeNamingManager();

            // Create the Naming Service
            final ServiceTarget target = updateContext.getServiceTarget();
            target.addService(NamingService.SERVICE_NAME, new NamingService(true)).install();

            // Create java: context service
            final JavaContextService javaContextService = new JavaContextService();
            target.addService(JavaContextService.SERVICE_NAME, javaContextService)
                .addDependency(NamingService.SERVICE_NAME)
                .install();

            final ContextService globalContextService = new ContextService("global");
            target.addService(JavaContextService.SERVICE_NAME.append("global"), globalContextService)
                 .addDependency(JavaContextService.SERVICE_NAME, Context.class, globalContextService.getParentContextInjector())
                 .install();

            addContextFactory(target, "app");
            addContextFactory(target, "module");
            addContextFactory(target, "comp");

            // Provide the {@link InitialContext} as OSGi service
            InitialContextFactoryService.addService(target);

            final JndiView jndiView = new JndiView();
            target.addService(ServiceName.JBOSS.append("naming", "jndi", "view"), jndiView)
                .addDependency(ServiceBuilder.DependencyType.OPTIONAL, ServiceName.JBOSS.append("mbean", "server"), MBeanServer.class, jndiView.getMBeanServerInjector())
                .install();
        }

        context.getSubModel().setEmptyObject();

        resultHandler.handleResultComplete(compensatingOperation);

        return Cancellable.NULL;
    }

    private static void addContextFactory(final ServiceTarget target, final String contextName) {
        final Reference appReference = NamespaceObjectFactory.createReference(contextName);
        final BinderService<Reference> binderService = new BinderService<Reference>(contextName, Values.immediateValue(appReference));
        target.addService(JavaContextService.SERVICE_NAME.append(contextName), binderService)
            .addDependency(JavaContextService.SERVICE_NAME, Context.class, binderService.getContextInjector())
            .setInitialMode(ServiceController.Mode.ACTIVE)
            .install();
    }
}
