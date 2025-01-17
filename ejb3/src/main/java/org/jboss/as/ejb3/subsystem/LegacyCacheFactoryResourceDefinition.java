/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022, Red Hat, Inc., and individual contributors
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
package org.jboss.as.ejb3.subsystem;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ReloadRequiredWriteAttributeHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.StringListAttributeDefinition;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelType;

/**
 * Defines a CacheFactoryBuilder instance which, during deployment, is used to configure, build and install a CacheFactory for the SFSB being deployed.
 * The CacheFactory resource instances defined here produce bean caches which are either:
 * - distributed and have passivation-enabled
 * - non distributed and do not have passivation-enabled
 * For passivation enabled CacheFactoryBuilders, the PassivationStoreResourceDefinition must define a supporting passivation store.
 *
 * @author Paul Ferraro
 */
@Deprecated
public class LegacyCacheFactoryResourceDefinition extends SimpleResourceDefinition {

    // capabilities not required as although we install CacheFactoryBuilder services, these do not depend on any defined clustering resources

    public static final StringListAttributeDefinition ALIASES = new StringListAttributeDefinition.Builder(EJB3SubsystemModel.ALIASES)
            .setXmlName(EJB3SubsystemXMLAttribute.ALIASES.getLocalName())
            .setRequired(false)
            .build();

    public static final SimpleAttributeDefinition PASSIVATION_STORE =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.PASSIVATION_STORE, ModelType.STRING, true)
                    .setXmlName(EJB3SubsystemXMLAttribute.PASSIVATION_STORE_REF.getLocalName())
                    .setAllowExpression(true)
                    .setFlags(AttributeAccess.Flag.RESTART_NONE)
                    .build();

    private static final AttributeDefinition[] ATTRIBUTES = { ALIASES, PASSIVATION_STORE };
    private static final LegacyCacheFactoryAdd ADD_HANDLER = new LegacyCacheFactoryAdd(ATTRIBUTES);
    private static final LegacyCacheFactoryRemove REMOVE_HANDLER = new LegacyCacheFactoryRemove(ADD_HANDLER);

    LegacyCacheFactoryResourceDefinition() {
        super(PathElement.pathElement(EJB3SubsystemModel.CACHE),
                EJB3Extension.getResourceDescriptionResolver(EJB3SubsystemModel.CACHE),
                ADD_HANDLER, REMOVE_HANDLER,
                OperationEntry.Flag.RESTART_NONE, OperationEntry.Flag.RESTART_RESOURCE_SERVICES);
        this.setDeprecated(EJB3Model.VERSION_10_0_0.getVersion());
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        ReloadRequiredWriteAttributeHandler handler = new ReloadRequiredWriteAttributeHandler(ATTRIBUTES);
        for (AttributeDefinition attribute: ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(attribute,  null, handler);
        }
    }
}
