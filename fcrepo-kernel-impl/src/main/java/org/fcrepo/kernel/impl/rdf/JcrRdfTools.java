/**
 * Copyright 2014 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.kernel.impl.rdf;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.propagate;
import static com.google.common.collect.Iterables.any;
import static com.hp.hpl.jena.graph.Triple.create;
import static com.hp.hpl.jena.rdf.model.ModelFactory.createDefaultModel;
import static com.hp.hpl.jena.rdf.model.ResourceFactory.createProperty;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static javax.jcr.PropertyType.REFERENCE;
import static javax.jcr.PropertyType.STRING;
import static javax.jcr.PropertyType.UNDEFINED;
import static javax.jcr.PropertyType.URI;
import static javax.jcr.PropertyType.WEAKREFERENCE;
import static org.fcrepo.kernel.RdfLexicon.HAS_MEMBER_OF_RESULT;
import static org.fcrepo.kernel.RdfLexicon.JCR_NAMESPACE;
import static org.fcrepo.kernel.RdfLexicon.isManagedPredicate;
import static org.fcrepo.kernel.impl.utils.FedoraTypesUtils.getDefinitionForPropertyName;
import static org.fcrepo.kernel.impl.utils.FedoraTypesUtils.isReferenceProperty;
import static org.fcrepo.kernel.utils.NamespaceTools.getNamespaceRegistry;
import static org.fcrepo.kernel.impl.utils.NodePropertiesTools.getReferencePropertyOriginalName;
import static org.slf4j.LoggerFactory.getLogger;

import java.net.URI;
import java.util.Iterator;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.ValueFactory;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.NodeTypeTemplate;
import javax.jcr.nodetype.PropertyDefinition;

import org.fcrepo.kernel.RdfLexicon;
import org.fcrepo.kernel.exception.MalformedRdfException;
import org.fcrepo.kernel.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.exception.ServerManagedPropertyException;
import org.fcrepo.kernel.identifiers.IdentifierConverter;
import org.fcrepo.kernel.impl.utils.NodePropertiesTools;
import org.fcrepo.kernel.impl.rdf.impl.FixityRdfContext;
import org.fcrepo.kernel.impl.rdf.impl.NamespaceRdfContext;
import org.fcrepo.kernel.impl.rdf.impl.PropertiesRdfContext;
import org.fcrepo.kernel.impl.rdf.impl.WorkspaceRdfContext;
import org.fcrepo.kernel.utils.FixityResult;
import org.fcrepo.kernel.utils.iterators.RdfStream;
import org.modeshape.jcr.api.NamespaceRegistry;
import org.modeshape.jcr.api.Namespaced;
import org.slf4j.Logger;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.hp.hpl.jena.datatypes.RDFDatatype;
import com.hp.hpl.jena.datatypes.xsd.XSDDateTime;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;

/**
 * A set of helpful tools for converting JCR properties to RDF
 *
 * @author Chris Beer
 * @since May 10, 2013
 */
public class JcrRdfTools {

    private static final Logger LOGGER = getLogger(JcrRdfTools.class);

    /**
     * A map of JCR namespaces to Fedora's RDF namespaces
     */
    public static BiMap<String, String> jcrNamespacesToRDFNamespaces =
        ImmutableBiMap.of(JCR_NAMESPACE,
                RdfLexicon.REPOSITORY_NAMESPACE);

    /**
     * A map of Fedora's RDF namespaces to the JCR equivalent
     */
    public static BiMap<String, String> rdfNamespacesToJcrNamespaces =
        jcrNamespacesToRDFNamespaces.inverse();

    private final IdentifierConverter<Resource,Node> graphSubjects;

    private Session session;

    /**
     * Factory method to create a new JcrRdfTools utility with a graph subjects
     * converter
     *
     * @param graphSubjects
     */
    public JcrRdfTools(final IdentifierConverter<Resource,Node> graphSubjects) {
        this(graphSubjects, null);
    }
    /**
     * Constructor with even more context.
     *
     * @param graphSubjects
     * @param session
     */
    public JcrRdfTools(final IdentifierConverter<Resource,Node> graphSubjects, final Session session) {
        this.graphSubjects = graphSubjects;
        this.session = session;
    }

    /**
     * Factory method to create a new JcrRdfTools instance
     *
     * @param idTranslator
     * @param session
     * @return new JcrRdfTools instance
     */
    public static JcrRdfTools withContext(final IdentifierConverter<Resource,Node> idTranslator,
        final Session session) {
        checkNotNull(idTranslator, "JcrRdfTools must operate with a non-null IdentifierTranslator for context!");
        return new JcrRdfTools(idTranslator, session);
    }

    /**
     * Convert a Fedora RDF Namespace into its JCR equivalent
     *
     * @param rdfNamespaceUri a namespace from an RDF document
     * @return the JCR namespace, or the RDF namespace if no matching JCR
     *         namespace is found
     */
    public static String getJcrNamespaceForRDFNamespace(
            final String rdfNamespaceUri) {
        if (rdfNamespacesToJcrNamespaces.containsKey(rdfNamespaceUri)) {
            return rdfNamespacesToJcrNamespaces.get(rdfNamespaceUri);
        }
        return rdfNamespaceUri;
    }

    /**
     * Convert a JCR namespace into an RDF namespace fit for downstream
     * consumption.
     *
     * @param jcrNamespaceUri a namespace from the JCR NamespaceRegistry
     * @return an RDF namespace for downstream consumption.
     */
    public static String getRDFNamespaceForJcrNamespace(
            final String jcrNamespaceUri) {
        if (jcrNamespacesToRDFNamespaces.containsKey(jcrNamespaceUri)) {
            return jcrNamespacesToRDFNamespaces.get(jcrNamespaceUri);
        }
        return jcrNamespaceUri;
    }

    /**
     * Get a model in which to collect statements of RDF extraction problems
     *
     * @return an empty model
     */
    public static Model getProblemsModel() {
        return createDefaultModel();
    }

    /**
     * Get an {@link RdfStream} for the given JCR NodeIterator
     *
     * @param nodeIterator
     * @param iteratorSubject
     * @return RdfStream for the given JCR NodeIterator
     * @throws RepositoryException
     */
    public RdfStream getJcrPropertiesModel(final Iterator<Node> nodeIterator,
            final Resource iteratorSubject) throws RepositoryException {

        final RdfStream results = new RdfStream();
        while (nodeIterator.hasNext()) {
            final Node node = nodeIterator.next();
            results.concat(new PropertiesRdfContext(node, graphSubjects));
            if (iteratorSubject != null) {
                results.concat(singleton(create(iteratorSubject.asNode(), HAS_MEMBER_OF_RESULT.asNode(), graphSubjects
                        .reverse().convert(node).asNode())));
            }
        }
        return results;
    }

    /**
     * Serialize the JCR fixity information in an {@link RdfStream}
     *
     * @param node
     * @param blobs
     * @return fixity information triples as an RdfStream
     * @throws RepositoryException
     */
    public RdfStream getJcrTriples(final Node node,
            final Iterable<FixityResult> blobs, final URI digest, final long size) throws RepositoryException {
        return new FixityRdfContext(node, graphSubjects, blobs, digest, size);
    }

    /**
     * Get an {@link RdfStream} of the registered JCR namespaces
     *
     * @return namespace triples as an RdfStream
     * @throws RepositoryException
     */
    public RdfStream getNamespaceTriples() throws RepositoryException {
        return new NamespaceRdfContext(session);
    }

    /**
     * Get an {@link RdfStream} of the registered JCR workspaces
     *
     * @return workspace triples as an RdfStream
     * @throws RepositoryException
     */
    public RdfStream getWorkspaceTriples(final IdentifierConverter<Resource,Node> subjects) throws RepositoryException {
        return new WorkspaceRdfContext(session, subjects);
    }

    /**
     * Decides whether the RDF representation of this {@link Node} will receive LDP Container status.
     *
     * @param node
     * @return true if the node will receive LDP Container status
     * @throws RepositoryException
     */
    public static boolean isContainer(final Node node) throws RepositoryException {
        return HAS_CHILD_NODE_DEFINITIONS.apply(node.getPrimaryNodeType())
                || any(ImmutableList.copyOf(node.getMixinNodeTypes()),
                        HAS_CHILD_NODE_DEFINITIONS);
    }

    static Predicate<NodeType> HAS_CHILD_NODE_DEFINITIONS =
        new Predicate<NodeType>() {

            @Override
            public boolean apply(final NodeType input) {
                return input.getChildNodeDefinitions().length > 0;
            }
        };

    /**
     * Determine if a predicate is an internal property of a node (and should
     * not be modified from external sources)
     *
     * @param subjectNode
     * @param predicate
     * @return True if a predicate is an internal property of a node
     */
    public boolean isInternalProperty(final Node subjectNode,
            final com.hp.hpl.jena.rdf.model.Property predicate) {
        return isManagedPredicate.apply(predicate);
    }

    /**
     * Create a JCR value from an RDFNode, either by using the given JCR
     * PropertyType or by looking at the RDFNode Datatype
     *
     * @param data an RDF Node (possibly with a DataType)
     * @param type a JCR PropertyType value
     * @return a JCR Value
     * @throws javax.jcr.RepositoryException
     */
    public Value createValue(final Node node, final RDFNode data, final int type)
        throws RepositoryException {
        final ValueFactory valueFactory = node.getSession().getValueFactory();
        return createValue(valueFactory, data, type);

    }

    /**
     * Create a JCR value from an RDFNode with the given JCR type
     * @param data
     * @param type
     * @return created JCR value
     * @throws RepositoryException
     */
    public Value createValue(final RDFNode data, final int type) throws RepositoryException {
        return createValue(session.getValueFactory(), data, type);
    }

    /**
     * Create a JCR value from an RDF node with the given JCR type
     * @param valueFactory
     * @param data
     * @param type
     * @return created value
     * @throws RepositoryException
     */
    public Value createValue(final ValueFactory valueFactory, final RDFNode data, final int type)
        throws RepositoryException {
        assert (valueFactory != null);

        if (data.isURIResource()
                && (type == REFERENCE || type == WEAKREFERENCE)) {
            // reference to another node (by path)
            try {
                final Node nodeFromGraphSubject = graphSubjects.convert(data.asResource());
                return valueFactory.createValue(nodeFromGraphSubject,
                        type == WEAKREFERENCE);
            } catch (final RepositoryRuntimeException e) {
                throw new MalformedRdfException("Unable to find referenced node", e);
            }
        } else if (!data.isURIResource() && (type == REFERENCE || type == WEAKREFERENCE)) {
            throw new ValueFormatException("Reference properties can only refer to URIs, not literals");
        } else if (data.isURIResource() || type == URI) {
            // some random opaque URI
            return valueFactory.createValue(data.toString(), PropertyType.URI);
        } else if (data.isResource()) {
            // a non-URI resource (e.g. a blank node)
            return valueFactory.createValue(data.toString(), UNDEFINED);
        } else if (data.isLiteral() && type == UNDEFINED) {
            // the JCR schema doesn't know what this should be; so introspect
            // the RDF and try to figure it out
            final Literal literal = data.asLiteral();
            final RDFDatatype dataType = literal.getDatatype();
            final Object rdfValue = literal.getValue();

            if (rdfValue instanceof Boolean) {
                return valueFactory.createValue((Boolean) rdfValue);
            } else if (rdfValue instanceof Byte
                    || (dataType != null && dataType.getJavaClass() == Byte.class)) {
                return valueFactory.createValue(literal.getByte());
            } else if (rdfValue instanceof Double) {
                return valueFactory.createValue((Double) rdfValue);
            } else if (rdfValue instanceof Float) {
                return valueFactory.createValue((Float) rdfValue);
            } else if (rdfValue instanceof Long
                    || (dataType != null && dataType.getJavaClass() == Long.class)) {
                return valueFactory.createValue(literal.getLong());
            } else if (rdfValue instanceof Short
                    || (dataType != null && dataType.getJavaClass() == Short.class)) {
                return valueFactory.createValue(literal.getShort());
            } else if (rdfValue instanceof Integer) {
                return valueFactory.createValue((Integer) rdfValue);
            } else if (rdfValue instanceof XSDDateTime) {
                return valueFactory.createValue(((XSDDateTime) rdfValue)
                        .asCalendar());
            } else {
                return valueFactory.createValue(literal.getString(), STRING);
            }

        } else {
            LOGGER.debug("Using default JCR value creation for RDF literal: {}",
                    data);
            return valueFactory.createValue(data.asLiteral().getString(), type);
        }
    }

    /**
     * Given an RDF predicate value (namespace URI + local name), figure out
     * what JCR property to use
     *
     * @param node the JCR node we want a property for
     * @param predicate the predicate to map to a property name
     * @return JCR property name
     * @throws RepositoryException
     */
    public String getPropertyNameFromPredicate(final Node node,
        final com.hp.hpl.jena.rdf.model.Property predicate)
        throws RepositoryException {
        final Map<String, String> s = emptyMap();
        return getPropertyNameFromPredicate(node, predicate, s);

    }

    /**
     * Given an RDF predicate value (namespace URI + local name), figure out
     * what JCR property to use
     *
     * @param node the JCR node we want a property for
     * @param predicate the predicate to map to a property name
     * @param namespaceMapping prefix to uri namespace mapping
     * @return the JCR property name
     * @throws RepositoryException
     */

    public String getPropertyNameFromPredicate(final Node node, final com.hp.hpl.jena.rdf.model.Property predicate,
                                               final Map<String, String> namespaceMapping) throws RepositoryException {

        final NamespaceRegistry namespaceRegistry =
            getNamespaceRegistry.apply(node);

        return getJcrNameForRdfNode(namespaceRegistry,
                                    predicate.getNameSpace(),
                                    predicate.getLocalName(),
                                    namespaceMapping);
    }

    /**
     * Get a property name for an RDF predicate
     * @param predicate
     * @return property name from the given predicate
     * @throws RepositoryException
     */
    public String getPropertyNameFromPredicate(final com.hp.hpl.jena.rdf.model.Property predicate)
        throws RepositoryException {

        final NamespaceRegistry namespaceRegistry =
            (org.modeshape.jcr.api.NamespaceRegistry) session.getWorkspace().getNamespaceRegistry();

        final Map<String, String> namespaceMapping = emptyMap();
        return getJcrNameForRdfNode(namespaceRegistry,
                                    predicate.getNameSpace(),
                                    predicate.getLocalName(),
                                    namespaceMapping);
    }

    /**
     * Get the JCR name for the given RDF resource
     * @param node
     * @param resource
     * @param namespaces
     * @return JCR name for the given RDF resource
     * @throws RepositoryException
     */
    public String getPropertyNameFromPredicate(final Node node,
                                               final Resource resource,
                                               final Map<String,String> namespaces) throws RepositoryException {
        final NamespaceRegistry namespaceRegistry = getNamespaceRegistry.apply(node);
        return getJcrNameForRdfNode(namespaceRegistry,
                                    resource.getNameSpace(),
                                    resource.getLocalName(),
                                    namespaces);
    }

    /**
     * Get the JCR property name for an RDF predicate
     *
     * @param namespaceRegistry
     * @param rdfNamespace
     * @param rdfLocalname
     * @param namespaceMapping
     * @return JCR property name for an RDF predicate
     * @throws RepositoryException
     */
    private String getJcrNameForRdfNode(final NamespaceRegistry namespaceRegistry,
                                        final String rdfNamespace,
                                        final String rdfLocalname,
                                        final Map<String, String> namespaceMapping)
        throws RepositoryException {

        final String prefix;

        final String namespace =
            getJcrNamespaceForRDFNamespace(rdfNamespace);

        assert (namespaceRegistry != null);

        if (namespaceRegistry.isRegisteredUri(namespace)) {
            LOGGER.debug("Discovered namespace: {} in namespace registry.",namespace);
            prefix = namespaceRegistry.getPrefix(namespace);
        } else {
            LOGGER.debug("Didn't discover namespace: {} in namespace registry.",namespace);
            final ImmutableBiMap<String, String> nsMap =
                ImmutableBiMap.copyOf(namespaceMapping);
            if (nsMap.containsValue(namespace)) {
                LOGGER.debug("Discovered namespace: {} in namespace map: {}.", namespace,
                        nsMap);
                prefix = nsMap.inverse().get(namespace);
                namespaceRegistry.registerNamespace(prefix, namespace);
            } else {
                prefix = namespaceRegistry.registerNamespace(namespace);
            }
        }

        final String propertyName = prefix + ":" + rdfLocalname;

        LOGGER.debug("Took RDF predicate {} and translated it to JCR property {}", namespace, propertyName);

        return propertyName;

    }

    /**
     * Given a node type and a property name, figure out an appropriate jcr value type
     * @param nodeType
     * @param propertyName
     * @return jcr value type
     * @throws RepositoryException
     */
    public int getPropertyType(final String nodeType, final String propertyName) throws RepositoryException {
        return getPropertyType(session.getWorkspace().getNodeTypeManager().getNodeType(nodeType), propertyName);

    }

    /**
     * Given a node type and a property name, figure out an appropraite jcr value type
     * @param nodeType
     * @param propertyName
     * @return jcr value type
     */
    public int getPropertyType(final NodeType nodeType, final String propertyName) {
        final PropertyDefinition[] propertyDefinitions = nodeType.getPropertyDefinitions();
        int type = UNDEFINED;
        for (final PropertyDefinition propertyDefinition : propertyDefinitions) {
            if (propertyDefinition.getName().equals(propertyName)) {
                if (type != UNDEFINED) {
                    return UNDEFINED;
                }

                type = propertyDefinition.getRequiredType();
            }
        }

        return type;
    }

    /**
     * Map a JCR property to an RDF property with the right namespace URI and
     * local name
     */
    public static Function<Property, com.hp.hpl.jena.rdf.model.Property> getPredicateForProperty =
            new Function<Property, com.hp.hpl.jena.rdf.model.Property>() {

                @Override
                public com.hp.hpl.jena.rdf.model.Property apply(
                        final Property property) {
                    LOGGER.trace("Creating predicate for property: {}",
                            property);
                    try {
                        if (property instanceof Namespaced) {
                            final Namespaced nsProperty = (Namespaced) property;
                            final String uri = nsProperty.getNamespaceURI();
                            final String localName = nsProperty.getLocalName();
                            final String rdfLocalName;

                            if (isReferenceProperty.apply(property)) {
                                rdfLocalName = getReferencePropertyOriginalName(localName);
                            } else {
                                rdfLocalName = localName;
                            }
                            return createProperty(
                                    getRDFNamespaceForJcrNamespace(uri),
                                                     rdfLocalName);
                        }
                        return createProperty(property.getName());
                    } catch (final RepositoryException e) {
                        throw propagate(e);
                    }

                }
            };


    /**
     * Add a mixin to a node
     * @param node
     * @param mixinResource
     * @param namespaces
     * @throws RepositoryException
     */
    public void addMixin(final Node node, final Resource mixinResource, final Map<String,String> namespaces)
            throws RepositoryException {

        final Session session = node.getSession();
        final String mixinName = getPropertyNameFromPredicate(node, mixinResource, namespaces);
        if (!repositoryHasType(session, mixinName)) {
            final NodeTypeManager mgr = session.getWorkspace().getNodeTypeManager();
            final NodeTypeTemplate type = mgr.createNodeTypeTemplate();
            type.setName(mixinName);
            type.setMixin(true);
            type.setQueryable(true);
            mgr.registerNodeType(type, false);
        }

        if (node.isNodeType(mixinName)) {
            LOGGER.trace("Subject {} is already a {}; skipping", node, mixinName);
            return;
        }

        if (node.canAddMixin(mixinName)) {
            LOGGER.debug("Adding mixin: {} to node: {}.", mixinName, node.getPath());
            node.addMixin(mixinName);
        } else {
            throw new MalformedRdfException("Could not persist triple containing type assertion: "
                    + mixinResource.toString()
                    + " because no such mixin/type can be added to this node: "
                    + node.getPath() + "!");
        }
    }

    /**
     * Add property to a node
     * @param node
     * @param predicate
     * @param value
     * @param namespaces
     * @throws RepositoryException
     */
    public void addProperty(final Node node,
                            final com.hp.hpl.jena.rdf.model.Property predicate,
                            final RDFNode value,
                            final Map<String,String> namespaces) throws RepositoryException {

        if (isManagedPredicate.apply(predicate)) {

            throw new ServerManagedPropertyException("Could not persist triple containing predicate "
                    + predicate.toString()
                    + " to node "
                    + node.getPath());
        }

        final String propertyName =
                getPropertyNameFromPredicate(node, predicate, namespaces);
        final Value v = createValue(node, value, getPropertyType(node, propertyName));
        new NodePropertiesTools().appendOrReplaceNodeProperty(graphSubjects, node, propertyName, v);
    }

    /**
     * Get the JCR property type ID for a given property name. If unsure, mark
     * it as UNDEFINED.
     *
     * @param node the JCR node to add the property on
     * @param propertyName the property name
     * @return a PropertyType value
     * @throws RepositoryException
     */
    public int getPropertyType(final Node node, final String propertyName)
            throws RepositoryException {
        LOGGER.debug("Getting type of property: {} from node: {}",
                propertyName, node);
        final PropertyDefinition def =
                getDefinitionForPropertyName(node, propertyName);

        if (def == null) {
            return UNDEFINED;
        }

        return def.getRequiredType();
    }

    protected boolean repositoryHasType(final Session session, final String mixinName) throws RepositoryException {
        return session.getWorkspace().getNodeTypeManager().hasNodeType(mixinName);
    }

    /**
     * Remove a mixin from a node
     * @param subjectNode
     * @param mixinResource
     * @param nsPrefixMap
     * @throws RepositoryException
     */
    public void removeMixin(final Node subjectNode,
                            final Resource mixinResource,
                            final Map<String, String> nsPrefixMap) throws RepositoryException {

        final String mixinName = getPropertyNameFromPredicate(subjectNode, mixinResource, nsPrefixMap);
        if (repositoryHasType(session, mixinName) && subjectNode.isNodeType(mixinName)) {
            subjectNode.removeMixin(mixinName);
        }

    }

    /**
     * Remove a property from a node
     * @param node
     * @param predicate
     * @param objectNode
     * @param nsPrefixMap
     * @throws RepositoryException
     */
    public void removeProperty(final Node node,
                               final com.hp.hpl.jena.rdf.model.Property predicate,
                               final RDFNode objectNode,
                               final Map<String, String> nsPrefixMap) throws RepositoryException {

        final String propertyName = getPropertyNameFromPredicate(node, predicate);

        if (isManagedPredicate.apply(predicate)) {

            throw new ServerManagedPropertyException("Could not remove triple containing predicate "
                    + predicate.toString()
                    + " to node "
                    + node.getPath());
        }

        // if the property doesn't exist, we don't need to worry about it.
        if (node.hasProperty(propertyName)) {
            final Value v = createValue(node, objectNode, getPropertyType(node, propertyName));

            new NodePropertiesTools().removeNodeProperty(graphSubjects, node, propertyName, v);
        }
    }

}