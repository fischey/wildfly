/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
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

package org.wildfly.extension.batch.jberet;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jberet.repository.JobRepository;
import org.jberet.spi.ContextClassLoaderJobOperatorContextSelector;
import org.jberet.spi.JobExecutor;
import org.jberet.spi.JobOperatorContext;
import org.jboss.as.controller.AbstractBoottimeAddStepHandler;
import org.jboss.as.controller.AbstractWriteAttributeHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.ReloadRequiredWriteAttributeHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.controller.operations.common.GenericSubsystemDescribeHandler;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.server.AbstractDeploymentChainStep;
import org.jboss.as.server.DeploymentProcessorTarget;
import org.jboss.as.server.deployment.Phase;
import org.jboss.as.server.deployment.jbossallxml.JBossAllXmlParserRegisteringProcessor;
import org.jboss.as.threads.ThreadFactoryResourceDefinition;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.wildfly.extension.batch.jberet._private.Capabilities;
import org.wildfly.extension.batch.jberet.deployment.BatchAttachments;
import org.wildfly.extension.batch.jberet.deployment.BatchCleanupProcessor;
import org.wildfly.extension.batch.jberet.deployment.BatchDependencyProcessor;
import org.wildfly.extension.batch.jberet.deployment.BatchDeploymentDescriptorParser_1_0;
import org.wildfly.extension.batch.jberet.deployment.BatchDeploymentDescriptorParser_2_0;
import org.wildfly.extension.batch.jberet.deployment.BatchDeploymentDescriptorParser_3_0;
import org.wildfly.extension.batch.jberet.deployment.BatchDeploymentResourceProcessor;
import org.wildfly.extension.batch.jberet.deployment.BatchEnvironmentProcessor;
import org.wildfly.extension.batch.jberet.job.repository.InMemoryJobRepositoryDefinition;
import org.wildfly.extension.batch.jberet.job.repository.JdbcJobRepositoryDefinition;
import org.wildfly.extension.batch.jberet.thread.pool.BatchThreadPoolResourceDefinition;
import org.wildfly.security.auth.server.SecurityDomain;

public class BatchSubsystemDefinition extends SimpleResourceDefinition {

    /**
     * The name of our subsystem within the model.
     */
    public static final String NAME = "batch-jberet";
    public static final PathElement SUBSYSTEM_PATH = PathElement.pathElement(SUBSYSTEM, NAME);
    static final String THREAD_FACTORY = "thread-factory";

    static final SimpleAttributeDefinition DEFAULT_JOB_REPOSITORY = SimpleAttributeDefinitionBuilder.create("default-job-repository", ModelType.STRING, false)
            .setAllowExpression(false)
            .setAttributeGroup("environment")
            .setAttributeMarshaller(AttributeMarshallers.NAMED)
            .setCapabilityReference(Capabilities.JOB_REPOSITORY_CAPABILITY.getName(), Capabilities.BATCH_CONFIGURATION_CAPABILITY)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition DEFAULT_THREAD_POOL = SimpleAttributeDefinitionBuilder.create("default-thread-pool", ModelType.STRING, false)
            .setAllowExpression(false)
            .setAttributeGroup("environment")
            .setAttributeMarshaller(AttributeMarshallers.NAMED)
            .setCapabilityReference(Capabilities.THREAD_POOL_CAPABILITY.getName(), Capabilities.BATCH_CONFIGURATION_CAPABILITY)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition RESTART_JOBS_ON_RESUME = SimpleAttributeDefinitionBuilder.create("restart-jobs-on-resume", ModelType.BOOLEAN, true)
            .setAllowExpression(true)
            .setDefaultValue(ModelNode.TRUE)
            .setAttributeParser(AttributeParsers.VALUE)
            .setAttributeMarshaller(AttributeMarshallers.VALUE)
            .build();

    static final SimpleAttributeDefinition SECURITY_DOMAIN = SimpleAttributeDefinitionBuilder.create("security-domain", ModelType.STRING, true)
            .setAttributeMarshaller(AttributeMarshallers.NAMED)
            .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
            .setCapabilityReference(Capabilities.SECURITY_DOMAIN_CAPABILITY, Capabilities.BATCH_CONFIGURATION_CAPABILITY)
            .setAccessConstraints(SensitiveTargetAccessConstraintDefinition.ELYTRON_SECURITY_DOMAIN_REF)
            .build();

    private final boolean registerRuntimeOnly;

    BatchSubsystemDefinition(final boolean registerRuntimeOnly) {
        super(new SimpleResourceDefinition.Parameters(SUBSYSTEM_PATH, BatchResourceDescriptionResolver.getResourceDescriptionResolver())
                .setAddHandler(BatchSubsystemAdd.INSTANCE)
                .setRemoveHandler(ReloadRequiredRemoveStepHandler.INSTANCE)
                .addCapabilities(Capabilities.BATCH_CONFIGURATION_CAPABILITY));
        this.registerRuntimeOnly = registerRuntimeOnly;
    }

    @Override
    public void registerOperations(final ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        resourceRegistration.registerOperationHandler(GenericSubsystemDescribeHandler.DEFINITION, GenericSubsystemDescribeHandler.INSTANCE);
    }

    @Override
    public void registerChildren(final ManagementResourceRegistration resourceRegistration) {
        super.registerChildren(resourceRegistration);
        resourceRegistration.registerSubModel(new InMemoryJobRepositoryDefinition());
        resourceRegistration.registerSubModel(new JdbcJobRepositoryDefinition());
        // thread-pool resource
        resourceRegistration.registerSubModel(new BatchThreadPoolResourceDefinition(registerRuntimeOnly));

        // thread-factory resource
        final ThreadFactoryResourceDefinition threadFactoryResource = new ThreadFactoryResourceDefinition();
        resourceRegistration.registerSubModel(threadFactoryResource);
    }

    @Override
    public void registerAttributes(final ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);
        final OperationStepHandler writeHandler = new ReloadRequiredWriteAttributeHandler(DEFAULT_JOB_REPOSITORY, DEFAULT_THREAD_POOL, SECURITY_DOMAIN);
        resourceRegistration.registerReadWriteAttribute(DEFAULT_JOB_REPOSITORY, null, writeHandler);
        resourceRegistration.registerReadWriteAttribute(DEFAULT_THREAD_POOL, null, writeHandler);
        resourceRegistration.registerReadWriteAttribute(SECURITY_DOMAIN, null, writeHandler);
        resourceRegistration.registerReadWriteAttribute(RESTART_JOBS_ON_RESUME, null, new AbstractWriteAttributeHandler<Boolean>(RESTART_JOBS_ON_RESUME) {
            @Override
            protected boolean applyUpdateToRuntime(final OperationContext context, final ModelNode operation, final String attributeName, final ModelNode resolvedValue, final ModelNode currentValue, final HandbackHolder<Boolean> handbackHolder) throws OperationFailedException {
                setValue(context, resolvedValue);
                return false;
            }

            @Override
            protected void revertUpdateToRuntime(final OperationContext context, final ModelNode operation, final String attributeName, final ModelNode valueToRestore, final ModelNode valueToRevert, final Boolean handback) throws OperationFailedException {
                setValue(context, valueToRestore);
            }

            private void setValue(final OperationContext context, final ModelNode value) {
                final BatchConfigurationService service = (BatchConfigurationService) context.getServiceRegistry(true)
                        .getService(context.getCapabilityServiceName(Capabilities.BATCH_CONFIGURATION_CAPABILITY.getName(), BatchConfiguration.class)).getService();
                service.setRestartOnResume(value.asBoolean());
            }
        });
    }

    /**
     * Handler responsible for adding the subsystem resource to the model.
     */
    static class BatchSubsystemAdd extends AbstractBoottimeAddStepHandler {
        static final BatchSubsystemAdd INSTANCE = new BatchSubsystemAdd();
        private final ContextClassLoaderJobOperatorContextSelector selector;

        private BatchSubsystemAdd() {
            super(DEFAULT_JOB_REPOSITORY, DEFAULT_THREAD_POOL, RESTART_JOBS_ON_RESUME, SECURITY_DOMAIN);
            selector = new ContextClassLoaderJobOperatorContextSelector(() -> JobOperatorContext.create(DefaultBatchEnvironment.INSTANCE));
            JobOperatorContext.setJobOperatorContextSelector(selector);
        }

        @Override
        protected void performBoottime(final OperationContext context, final ModelNode operation, final ModelNode model)
                throws OperationFailedException {
            // Check if the request-controller subsystem exists
            final boolean rcPresent = context.hasOptionalCapability("org.wildfly.request-controller", null, null);

            context.addStep(new AbstractDeploymentChainStep() {
                public void execute(DeploymentProcessorTarget processorTarget) {
                    final JBossAllXmlParserRegisteringProcessor<Object> jbossAllProcessor = JBossAllXmlParserRegisteringProcessor.builder()
                            .addParser(BatchDeploymentDescriptorParser_1_0.ROOT_ELEMENT, BatchAttachments.BATCH_ENVIRONMENT_META_DATA, new BatchDeploymentDescriptorParser_1_0())
                            .addParser(BatchDeploymentDescriptorParser_2_0.ROOT_ELEMENT, BatchAttachments.BATCH_ENVIRONMENT_META_DATA, new BatchDeploymentDescriptorParser_2_0())
                            .addParser(BatchDeploymentDescriptorParser_3_0.ROOT_ELEMENT, BatchAttachments.BATCH_ENVIRONMENT_META_DATA, new BatchDeploymentDescriptorParser_3_0())
                            .build();

                    processorTarget.addDeploymentProcessor(BatchSubsystemDefinition.NAME,
                            Phase.STRUCTURE, Phase.STRUCTURE_REGISTER_JBOSS_ALL_BATCH, jbossAllProcessor);
                    processorTarget.addDeploymentProcessor(NAME,
                            Phase.DEPENDENCIES, Phase.DEPENDENCIES_BATCH, new BatchDependencyProcessor());
                    processorTarget.addDeploymentProcessor(NAME,
                            Phase.POST_MODULE, Phase.POST_MODULE_BATCH_ENVIRONMENT, new BatchEnvironmentProcessor(rcPresent, selector));
                    processorTarget.addDeploymentProcessor(NAME,
                            Phase.INSTALL, Phase.INSTALL_BATCH_RESOURCES, new BatchDeploymentResourceProcessor(NAME));
                    processorTarget.addDeploymentProcessor(NAME,
                            Phase.CLEANUP, Phase.CLEANUP_BATCH, new BatchCleanupProcessor());

                }
            }, OperationContext.Stage.RUNTIME);

            final ModelNode defaultJobRepository = DEFAULT_JOB_REPOSITORY.resolveModelAttribute(context, model);
            final ModelNode defaultThreadPool = DEFAULT_THREAD_POOL.resolveModelAttribute(context, model);
            final ModelNode securityDomain = SECURITY_DOMAIN.resolveModelAttribute(context, model);
            final boolean restartOnResume = RESTART_JOBS_ON_RESUME.resolveModelAttribute(context, model).asBoolean();

            final ServiceTarget target = context.getServiceTarget();
            final ServiceName sn = context.getCapabilityServiceName(Capabilities.BATCH_CONFIGURATION_CAPABILITY.getName(), BatchConfiguration.class);
            final ServiceBuilder<?> serviceBuilder = target.addService(sn);
            final Consumer<BatchConfiguration> batchConfigurationConsumer = serviceBuilder.provides(sn);
            final Supplier<JobRepository> jobRepositorySupplier = serviceBuilder.requires(
                    context.getCapabilityServiceName(Capabilities.JOB_REPOSITORY_CAPABILITY.getName(), defaultJobRepository.asString(), JobRepository.class));
            final Supplier<JobExecutor> jobExecutorSupplier = serviceBuilder.requires(
                    context.getCapabilityServiceName(Capabilities.THREAD_POOL_CAPABILITY.getName(), defaultThreadPool.asString(), JobExecutor.class));
            final Supplier<SecurityDomain> securityDomainSupplier = securityDomain.isDefined()
                    ? serviceBuilder.requires(context.getCapabilityServiceName(Capabilities.SECURITY_DOMAIN_CAPABILITY, securityDomain.asString(), SecurityDomain.class))
                    : null;

            // Only start this service if there are deployments present, allow it to be stopped as deployments
            // are removed.
            serviceBuilder.setInitialMode(ServiceController.Mode.ON_DEMAND);
            final BatchConfigurationService service = new BatchConfigurationService(batchConfigurationConsumer, jobRepositorySupplier, jobExecutorSupplier, securityDomainSupplier);
            service.setRestartOnResume(restartOnResume);
            serviceBuilder.setInstance(service);
            serviceBuilder.install();
        }
    }
}
