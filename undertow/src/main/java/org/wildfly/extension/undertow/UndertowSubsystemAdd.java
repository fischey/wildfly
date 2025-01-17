/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
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

package org.wildfly.extension.undertow;

import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jboss.as.controller.AbstractBoottimeAddStepHandler;
import org.jboss.as.controller.CapabilityServiceBuilder;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.server.AbstractDeploymentChainStep;
import org.jboss.as.server.DeploymentProcessorTarget;
import org.jboss.as.server.deployment.Phase;
import org.jboss.as.server.deployment.jbossallxml.JBossAllXmlParserRegisteringProcessor;
import org.jboss.as.web.common.SharedTldsMetaDataBuilder;
import org.jboss.as.web.session.SharedSessionManagerConfig;
import org.jboss.dmr.ModelNode;

import org.wildfly.extension.undertow.deployment.DefaultDeploymentMappingProvider;
import org.wildfly.extension.undertow.deployment.DefaultSecurityDomainProcessor;
import org.wildfly.extension.undertow.deployment.DeploymentRootExplodedMountProcessor;
import org.wildfly.extension.undertow.deployment.EarContextRootProcessor;
import org.wildfly.extension.undertow.deployment.ExternalTldParsingDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.JBossWebParsingDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.SecurityDomainResolvingProcessor;
import org.wildfly.extension.undertow.deployment.ServletContainerInitializerDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.SharedSessionManagerDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.TldParsingDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.UndertowDependencyProcessor;
import org.wildfly.extension.undertow.deployment.UndertowDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.UndertowHandlersDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.UndertowJSRWebSocketDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.UndertowServletContainerDependencyProcessor;
import org.wildfly.extension.undertow.deployment.WarAnnotationDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.WarDeploymentInitializingProcessor;
import org.wildfly.extension.undertow.deployment.WarMetaDataProcessor;
import org.wildfly.extension.undertow.deployment.WarStructureDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.WebFragmentParsingDeploymentProcessor;
import org.wildfly.extension.undertow.deployment.WebJBossAllParser;
import org.wildfly.extension.undertow.deployment.WebParsingDeploymentProcessor;
import org.wildfly.extension.undertow.logging.UndertowLogger;
import org.wildfly.extension.undertow.session.SharedSessionConfigParser;
import org.wildfly.extension.undertow.session.SharedSessionConfigSchema;

import static org.wildfly.extension.undertow.UndertowRootDefinition.HTTP_INVOKER_RUNTIME_CAPABILITY;


/**
 * Handler responsible for adding the subsystem resource to the model
 *
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a> (c) 2012 Red Hat Inc.
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 * @author Flavia Rainone
 */
class UndertowSubsystemAdd extends AbstractBoottimeAddStepHandler {


    private final Predicate<String> knownSecurityDomain;

    UndertowSubsystemAdd(Predicate<String> knownSecurityDomain) {
        super(UndertowRootDefinition.ATTRIBUTES);
        this.knownSecurityDomain = knownSecurityDomain;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void performBoottime(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {

        try {
            Class.forName("org.apache.jasper.compiler.JspRuntimeContext", true, this.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            UndertowLogger.ROOT_LOGGER.couldNotInitJsp(e);
        }
        final ModelNode model = resource.getModel();

        final String defaultVirtualHost = UndertowRootDefinition.DEFAULT_VIRTUAL_HOST.resolveModelAttribute(context, model).asString();
        final String defaultContainer = UndertowRootDefinition.DEFAULT_SERVLET_CONTAINER.resolveModelAttribute(context, model).asString();
        final String defaultServer = UndertowRootDefinition.DEFAULT_SERVER.resolveModelAttribute(context, model).asString();
        final boolean stats = UndertowRootDefinition.STATISTICS_ENABLED.resolveModelAttribute(context, model).asBoolean();
        final String defaultSecurityDomain = UndertowRootDefinition.DEFAULT_SECURITY_DOMAIN.resolveModelAttribute(context, model).asString();

        final ModelNode instanceIdModel = UndertowRootDefinition.INSTANCE_ID.resolveModelAttribute(context, model);
        final String instanceId = instanceIdModel.isDefined() ? instanceIdModel.asString() : null;

        final boolean obfuscateSessionRoute = UndertowRootDefinition.OBFUSCATE_SESSION_ROUTE.resolveModelAttribute(context, model).asBoolean();

        DefaultDeploymentMappingProvider.instance().clear();//we clear provider on system boot, as on reload it could cause issues.

        final CapabilityServiceBuilder<?> csb = context.getCapabilityServiceTarget().addCapability(UndertowRootDefinition.UNDERTOW_CAPABILITY);
        final Consumer<UndertowService> usConsumer = csb.provides(UndertowRootDefinition.UNDERTOW_CAPABILITY, UndertowService.UNDERTOW);
        csb.setInstance(new UndertowService(usConsumer, defaultContainer, defaultServer, defaultVirtualHost, instanceId, obfuscateSessionRoute, stats));
        csb.install();

        context.addStep(new AbstractDeploymentChainStep() {
            @Override
            protected void execute(DeploymentProcessorTarget processorTarget) {

                final SharedTldsMetaDataBuilder sharedTldsBuilder = new SharedTldsMetaDataBuilder(model.clone());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.STRUCTURE, Phase.STRUCTURE_EXPLODED_MOUNT, new DeploymentRootExplodedMountProcessor());
                JBossAllXmlParserRegisteringProcessor.Builder builder = JBossAllXmlParserRegisteringProcessor.builder();
                for (SharedSessionConfigSchema schema : EnumSet.allOf(SharedSessionConfigSchema.class)) {
                    builder.addParser(schema.getName(), SharedSessionManagerConfig.ATTACHMENT_KEY, new SharedSessionConfigParser(schema));
                }
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.STRUCTURE, Phase.STRUCTURE_REGISTER_JBOSS_ALL_UNDERTOW_SHARED_SESSION, builder.build());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.STRUCTURE, Phase.STRUCTURE_REGISTER_JBOSS_ALL_WEB, new JBossAllXmlParserRegisteringProcessor<>(WebJBossAllParser.ROOT_ELEMENT, WebJBossAllParser.ATTACHMENT_KEY, new WebJBossAllParser()));
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.STRUCTURE, Phase.STRUCTURE_WAR_DEPLOYMENT_INIT, new WarDeploymentInitializingProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.STRUCTURE, Phase.STRUCTURE_WAR, new WarStructureDeploymentProcessor(sharedTldsBuilder));
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_WEB_DEPLOYMENT, new WebParsingDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_WEB_DEPLOYMENT_FRAGMENT, new WebFragmentParsingDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_JBOSS_WEB_DEPLOYMENT, new JBossWebParsingDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_ANNOTATION_WAR, new WarAnnotationDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_EAR_CONTEXT_ROOT, new EarContextRootProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_WEB_MERGE_METADATA, new WarMetaDataProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_TLD_DEPLOYMENT, new TldParsingDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_WEB_COMPONENTS, new org.wildfly.extension.undertow.deployment.WebComponentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.PARSE, Phase.PARSE_UNDERTOW_DEFAULT_SECURITY_DOMAIN, new DefaultSecurityDomainProcessor(defaultSecurityDomain));

                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.DEPENDENCIES, Phase.DEPENDENCIES_WAR_MODULE, new UndertowDependencyProcessor());

                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.POST_MODULE, Phase.POST_MODULE_UNDERTOW_WEBSOCKETS, new UndertowJSRWebSocketDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.POST_MODULE, Phase.POST_MODULE_UNDERTOW_HANDLERS, new UndertowHandlersDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.POST_MODULE, Phase.POST_MODULE_EXTERNAL_TAGLIB, new ExternalTldParsingDeploymentProcessor());
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.POST_MODULE, Phase.POST_MODULE_UNDERTOW_SERVLET_CONTAINER_DEPENDENCY, new UndertowServletContainerDependencyProcessor(defaultContainer));


                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.INSTALL, Phase.INSTALL_SHARED_SESSION_MANAGER, new SharedSessionManagerDeploymentProcessor(defaultServer));

                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.INSTALL, Phase.INSTALL_SERVLET_INIT_DEPLOYMENT, new ServletContainerInitializerDeploymentProcessor());

                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.INSTALL, Phase.INSTALL_WEB_RESOLVE_SECURITY_DOMAIN, new SecurityDomainResolvingProcessor(defaultSecurityDomain, knownSecurityDomain));
                processorTarget.addDeploymentProcessor(UndertowExtension.SUBSYSTEM_NAME, Phase.INSTALL, Phase.INSTALL_WAR_DEPLOYMENT, new UndertowDeploymentProcessor(defaultVirtualHost, defaultContainer, defaultServer, knownSecurityDomain));

            }
        }, OperationContext.Stage.RUNTIME);

        context.getCapabilityServiceTarget()
                .addCapability(HTTP_INVOKER_RUNTIME_CAPABILITY)
                .setInstance(new RemoteHttpInvokerService())
                .install();
    }

}
