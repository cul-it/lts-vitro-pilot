/* $This file is distributed under the terms of the license in /doc/license.txt$ */

package edu.cornell.mannlib.vitro.webapp.reasoner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hp.hpl.jena.rdf.model.NodeIterator;
import com.hp.hpl.jena.rdf.model.Property;
import edu.cornell.mannlib.vitro.webapp.rdfservice.ResultSetConsumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.query.ResultSetFactory;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.shared.Lock;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

import edu.cornell.mannlib.vitro.webapp.dao.VitroVocabulary;
import edu.cornell.mannlib.vitro.webapp.modelaccess.ModelNames;
import edu.cornell.mannlib.vitro.webapp.modules.searchIndexer.SearchIndexer;
import edu.cornell.mannlib.vitro.webapp.rdfservice.ChangeSet;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFService;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFServiceException;

public class ABoxRecomputer {

    private static final Log log = LogFactory.getLog(ABoxRecomputer.class);

    private final SearchIndexer searchIndexer;

    private OntModel tboxModel;             // asserted and inferred TBox axioms
    private OntModel aboxModel;
    private RDFService rdfService;
    private SimpleReasoner simpleReasoner;
    private Object lock1 = new Object();
    private volatile boolean recomputing = false;
    private boolean stopRequested = false;

    private final int BATCH_SIZE = 100;
    private final int REPORTING_INTERVAL = 1000;

    /**
     * @param tboxModel - input.  This model contains both asserted and inferred TBox axioms
     * @param aboxModel - input.  This model contains asserted ABox statements
     */
    public ABoxRecomputer(OntModel tboxModel,
            OntModel aboxModel,
            RDFService rdfService,
            SimpleReasoner simpleReasoner,
            SearchIndexer searchIndexer) {
        this.tboxModel = tboxModel;
        this.aboxModel = aboxModel;
        this.rdfService = rdfService;
        this.simpleReasoner = simpleReasoner;
        this.searchIndexer = searchIndexer;
        recomputing = false;
        stopRequested = false;		
    }

    /**
     * Returns true if the recomputer is in the process of recomputing
     * all inferences.
     */
    public boolean isRecomputing() {
        return recomputing;
    }

    /**
     * Recompute all inferences.
     */
    public void recompute() {	
        synchronized (lock1) {
            if (recomputing) {
                return;
            } else {
                recomputing = true;
            }
        }
        try {
            if  (searchIndexer != null) {
                searchIndexer.pause();
                // Register now that we want to rebuild the index when we unpause
                // This allows the indexer to optimize behaviour whilst paused
                searchIndexer.rebuildIndex();
            }
            // Create a type cache for this execution and pass it to the recompute function
            // Ensures that caches are only valid for the length of one recompute
            recomputeABox(new TypeCaches());
        } finally {
            if  (searchIndexer != null) {
                searchIndexer.unpause();
            }
            synchronized (lock1) {
                recomputing = false;	    		
            }
        }
    }

    /*
     * Recompute the entire ABox inference graph.
     */
    protected void recomputeABox(TypeCaches caches) {
        log.info("Recomputing ABox inferences.");
        log.info("Finding individuals in ABox.");
        Collection<String> individuals = this.getAllIndividualURIs();
        log.info("Recomputing inferences for " + individuals.size() + " individuals");
        long start = System.currentTimeMillis();
        int numInds = 0;
        Model rebuildModel = ModelFactory.createDefaultModel();
        Model additionalInferences = ModelFactory.createDefaultModel();
        List<String> individualsInBatch = new ArrayList<String>();
        Iterator<String> individualIt = individuals.iterator();
        while (individualIt.hasNext()) {
            String individualURI = individualIt.next();
            try {
                additionalInferences.add(recomputeIndividual(
                        individualURI, rebuildModel, caches));
                numInds++;
                individualsInBatch.add(individualURI);
                boolean batchFilled = (numInds % BATCH_SIZE) == 0;
                boolean reportingInterval = (numInds % REPORTING_INTERVAL) == 0;
                if (batchFilled || !individualIt.hasNext()) {
                    log.debug(rebuildModel.size() + " total inferences");
                    updateInferenceModel(rebuildModel, individualsInBatch);
                    rebuildModel.removeAll();
                    individualsInBatch.clear();
                }
                if (reportingInterval) {
                    log.info("Still recomputing inferences (" 
                            + numInds + "/" + individuals.size() + " individuals)");
                    log.info((System.currentTimeMillis() - start) / numInds + " ms per individual");
                }
                if (stopRequested) {
                    log.info("a stopRequested signal was received during recomputeABox. Halting Processing.");
                    return;
                }
            } catch (Exception e) {
                log.error("Error recomputing inferences for individual <" + individualURI + ">", e);
            }
        }
        if(additionalInferences.size() > 0) {
            log.info("Writing additional inferences generated by reasoner plugins.");
            ChangeSet change = rdfService.manufactureChangeSet();
            change.addAddition(makeN3InputStream(additionalInferences), 
                    RDFService.ModelSerializationFormat.N3, ModelNames.ABOX_INFERENCES);
            try {
                rdfService.changeSetUpdate(change);
            } catch (RDFServiceException e) {
                log.error("Unable to write additional inferences from reasoner plugins", e);
            }
        }
        log.info("Finished recomputing inferences");
    }

    private static final boolean RUN_PLUGINS = true;
    private static final boolean SKIP_PLUGINS = !RUN_PLUGINS;

    private Model recomputeIndividual(String individualURI, 
            Model rebuildModel, TypeCaches caches) throws RDFServiceException {
        long start = System.currentTimeMillis();
        Model assertions = getAssertions(individualURI);
        log.trace((System.currentTimeMillis() - start) + " ms to get assertions.");
        Model additionalInferences = recomputeIndividual(
                individualURI, null, assertions, rebuildModel, caches, RUN_PLUGINS);

        if (simpleReasoner.getSameAsEnabled()) {
            Set<String> sameAsInds = getSameAsIndividuals(individualURI);
            for (String sameAsInd : sameAsInds) {
                // sameAs for plugins is handled by the SimpleReasoner
                Model sameAsIndAssertions = getAssertions(sameAsInd);
                recomputeIndividual(
                        sameAsInd, individualURI, sameAsIndAssertions, rebuildModel, caches, SKIP_PLUGINS);
                rebuildModel.add(
                        rewriteInferences(getAssertions(sameAsInd), individualURI));
                Resource indRes = ResourceFactory.createResource(individualURI);
                Resource sameAsIndRes = ResourceFactory.createResource(sameAsInd); 
                if(!assertions.contains(indRes, OWL.sameAs, sameAsIndRes)) {
                    rebuildModel.add(indRes, OWL.sameAs, sameAsIndRes);
                }
            }
        }
        return additionalInferences;
    }

    /**
     * Adds inferences to temporary rebuildmodel
     * @param individualURI
     * @param rebuildModel
     * @return any additional inferences produced by plugins that affect other 
     *         individuals
     */
    private Model recomputeIndividual(String individualURI, String aliasURI, 
            Model assertions, Model rebuildModel, TypeCaches caches, boolean runPlugins)
                    throws RDFServiceException {

        Model additionalInferences = ModelFactory.createDefaultModel();
        Resource individual = ResourceFactory.createResource(individualURI);

        long start = System.currentTimeMillis();
        Model types = ModelFactory.createDefaultModel();
        types.add(assertions.listStatements(null, RDF.type, (RDFNode) null));
        Model inferredTypes = rewriteInferences(getInferredTypes(individual, types, caches), aliasURI);
        rebuildModel.add(inferredTypes);
        log.trace((System.currentTimeMillis() - start) + " to infer " + inferredTypes.size() + " types");

        start = System.currentTimeMillis();
        types.add(inferredTypes);
        Model mst = getMostSpecificTypes(individual, types, caches);
        rebuildModel.add(rewriteInferences(mst, aliasURI));
        log.trace((System.currentTimeMillis() - start) + " to infer " + mst.size() + " mostSpecificTypes");

        start = System.currentTimeMillis();
        Model inferredInvs = getInferredInverseStatements(individualURI);
        inferredInvs.remove(assertions);
        rebuildModel.add(rewriteInferences(inferredInvs, aliasURI));
        log.trace((System.currentTimeMillis() - start) + " to infer " + inferredInvs.size() + " inverses");

        List<ReasonerPlugin> pluginList = simpleReasoner.getPluginList();
        if (runPlugins && pluginList.size() > 0) {
            Model tmpModel = ModelFactory.createDefaultModel();
            StmtIterator sit = assertions.listStatements();
            while (sit.hasNext()) {
                Statement s = sit.nextStatement();
                for (ReasonerPlugin plugin : pluginList) {
                    plugin.addedABoxStatement(s, aboxModel, tmpModel, tboxModel);
                }
            }
            StmtIterator tmpIt = tmpModel.listStatements();
            while(tmpIt.hasNext()) {
                Statement tmpStmt = tmpIt.nextStatement();
                if(individual.equals(tmpStmt.getSubject())) {
                    rebuildModel.add(tmpStmt);
                } else {
                    additionalInferences.add(tmpStmt);
                }
            }
        }
        return additionalInferences;
    }

    private Model getAssertions(String individualURI) throws RDFServiceException {
        String queryStr = "CONSTRUCT { \n" +
                "    <" + individualURI + "> ?p ?value \n" +
                "} WHERE { \n" +
                "    GRAPH ?g { \n" +
                "        <" + individualURI + "> ?p ?value \n" +
                "    } \n" +
                "    FILTER (?g != <" + ModelNames.ABOX_INFERENCES + ">)\n" +
                "} \n";

        Model model = ModelFactory.createDefaultModel();
        rdfService.sparqlConstructQuery(queryStr, model);
        return model;
    }

    private Model getInferredTypes(Resource individual, Model assertedTypes, TypeCaches caches) {
        if (caches == null) {
            return getInferredTypes(individual, assertedTypes);
        }

        TypeList key = new TypeList(assertedTypes, RDF.type);
        Model inferredTypes = caches.getInferredTypesToModel(key, individual);
        if (inferredTypes == null) {
            inferredTypes = getInferredTypes(individual, assertedTypes);
            caches.cacheInferredTypes(key, inferredTypes);
        }

        return inferredTypes;
    }

    private Model getInferredTypes(Resource individual, Model assertedTypes) {
        new TypeList(assertedTypes, RDF.type);
        String queryStr = "CONSTRUCT { \n" +
                "    <" + individual.getURI() + "> a ?type \n" +
                "} WHERE { \n" +
                "    <" + individual.getURI() + "> a ?assertedType .\n" +
                "    { ?assertedType <" + RDFS.subClassOf.getURI() + "> ?type } \n" +
                "     UNION \n" +
                "    { ?assertedType <" + OWL.equivalentClass.getURI() + "> ?type } \n" +
                "    FILTER (isURI(?type)) \n" +
                "    FILTER NOT EXISTS { \n" +
                "        <" + individual.getURI() + "> a ?type \n" +
                "    } \n" +
                "} \n";
        Model union = ModelFactory.createUnion(assertedTypes, tboxModel);
        tboxModel.enterCriticalSection(Lock.READ);
        try {
            Query q = QueryFactory.create(queryStr);
            QueryExecution qe = QueryExecutionFactory.create(q, union);
            return qe.execConstruct();
        } finally {
            tboxModel.leaveCriticalSection();
        }
    }

    private Model getMostSpecificTypes(Resource individual, Model assertedTypes, TypeCaches caches) {
        if (caches == null) {
            return getMostSpecificTypes(individual, assertedTypes);
        }

        TypeList key = new TypeList(assertedTypes, RDF.type);
        Model mostSpecificTypes = caches.getMostSpecificTypesToModel(key, individual);
        if (mostSpecificTypes == null) {
            mostSpecificTypes = getMostSpecificTypes(individual, assertedTypes);
            caches.cacheMostSpecificTypes(key, mostSpecificTypes);
        }

        return mostSpecificTypes;
    }

    private Model getMostSpecificTypes(Resource individual, Model assertedTypes) {
        String queryStr = "CONSTRUCT { \n" +
                "    <" + individual.getURI() + "> <" + VitroVocabulary.MOST_SPECIFIC_TYPE + "> ?type \n" + 
                "} WHERE { \n" +
                "    <" + individual.getURI() + "> a ?type .\n" +
                "    FILTER (isURI(?type)) \n" +
                "    FILTER NOT EXISTS { \n" +
                "        <" + individual.getURI() + "> a ?type2 . \n" +
                "        ?type2 <" + RDFS.subClassOf.getURI() + "> ?type. \n" +
                "    } \n" +
                "    FILTER NOT EXISTS { \n" +
                "        <" + individual.getURI() + "> <" + VitroVocabulary.MOST_SPECIFIC_TYPE + "> ?type \n" +
                "    } \n" +
                "} \n";
        Model union = ModelFactory.createUnion(assertedTypes, tboxModel);
        tboxModel.enterCriticalSection(Lock.READ);
        try {
            Query q = QueryFactory.create(queryStr);
            QueryExecution qe = QueryExecutionFactory.create(q, union);
            return qe.execConstruct();
        } finally {
            tboxModel.leaveCriticalSection();
        }
    }

    private Model getInferredInverseStatements(String individualURI) throws RDFServiceException {
        String queryStr = "CONSTRUCT { \n" +
                "    <" + individualURI + "> ?inv ?value \n" +
                "} WHERE { \n" +
                "    GRAPH ?gr { \n" +
                "        ?value ?prop <" + individualURI + "> \n" +
                "    } \n" +
                "   FILTER (isURI(?value)) \n" +
                "   FILTER (?gr != <" + ModelNames.ABOX_INFERENCES + ">) \n" +
                "    { ?prop <" + OWL.inverseOf.getURI() + "> ?inv } \n" +
                "     UNION \n" +
                "    { ?inv <" + OWL.inverseOf.getURI() + "> ?prop } \n" +
                "} \n";

        Model model = ModelFactory.createDefaultModel();
        rdfService.sparqlConstructQuery(queryStr, model);
        return model;
    }

    private Model rewriteInferences(Model inferences, String aliasURI) {
        if (aliasURI == null) {
            return inferences;
        }
        Model rewrite = ModelFactory.createDefaultModel();
        Resource alias = ResourceFactory.createResource(aliasURI);
        StmtIterator sit = inferences.listStatements();
        while(sit.hasNext()) {
            Statement stmt = sit.nextStatement();
            rewrite.add(alias, stmt.getPredicate(), stmt.getObject());
        }
        return rewrite;
    }

    /*
     * Get the URIs for all individuals in the system
     */
    protected Collection<String> getAllIndividualURIs() {
        HashSet<String> individualURIs = new HashSet<String>();
        List<String> classList = new ArrayList<String>();
        tboxModel.enterCriticalSection(Lock.READ);
        try {
            StmtIterator classIt = tboxModel.listStatements(
                    (Resource) null, RDF.type, OWL.Class);
            while(classIt.hasNext()) {
                Statement stmt = classIt.nextStatement();
                if(stmt.getSubject().isURIResource() 
                        && stmt.getSubject().getURI() != null 
                        && !stmt.getSubject().getURI().isEmpty()) {
                    classList.add(stmt.getSubject().getURI());
                }
            }
        } finally {
            tboxModel.leaveCriticalSection();
        }
        for (String classURI : classList) {
            String queryString = "SELECT ?s WHERE { ?s a <" + classURI + "> } ";
            getIndividualURIs(queryString, individualURIs);
        }
        return individualURIs;
    }

    protected void getIndividualURIs(String queryString, Set<String> individuals) {
        int batchSize = 50000;
        int offset = 0;
        boolean done = false;
        while (!done) {
            String queryStr = queryString + " LIMIT " + batchSize + " OFFSET " + offset;
            if(log.isDebugEnabled()) {
                log.debug(queryStr);
            }
            ResultSet results = null;
            try {
                InputStream in = rdfService.sparqlSelectQuery(queryStr, RDFService.ResultFormat.JSON);
                results = ResultSetFactory.fromJSON(in);
            } catch (RDFServiceException e) {
                throw new RuntimeException(e);
            }
            if (!results.hasNext()) {
                done = true;
            }
            while (results.hasNext()) {
                QuerySolution solution = results.next();
                Resource resource = solution.getResource("s");

                if ((resource != null) && !resource.isAnon()) {
                    individuals.add(resource.getURI());
                }					
            }
            if(log.isDebugEnabled()) {
                log.debug(individuals.size() + " in set");
            }
            offset += batchSize;
        }

    }

    protected void addInferenceStatementsFor(String individualUri, Model addTo) throws RDFServiceException {
        StringBuilder builder = new StringBuilder();
        builder.append("CONSTRUCT\n")
                .append("{\n")
                .append("   <" + individualUri + "> ?p ?o .\n")
                .append("}\n")
                .append("WHERE\n")
                .append("{\n")
                .append("   GRAPH <").append(ModelNames.ABOX_INFERENCES).append(">\n")
                .append("   {\n")
                .append("       <" + individualUri + "> ?p ?o .\n")
                .append("   }\n")
                .append("}\n");

        rdfService.sparqlConstructQuery(builder.toString(), addTo);
    }

    /*
     * reconcile a set of inferences into the application inference model
     */
    protected void updateInferenceModel(Model rebuildModel, 
            Collection<String> individuals) throws RDFServiceException {
        Model existing = ModelFactory.createDefaultModel();
        for (String individualURI : individuals) {
            addInferenceStatementsFor(individualURI, existing);
        }
        Model retractions = existing.difference(rebuildModel);
        Model additions = rebuildModel.difference(existing);
        if (additions.size() > 0 || retractions.size() > 0) {
            long start = System.currentTimeMillis();
            ChangeSet change = rdfService.manufactureChangeSet();
            if (retractions.size() > 0) {
                change.addRemoval(makeN3InputStream(retractions),
                        RDFService.ModelSerializationFormat.N3, ModelNames.ABOX_INFERENCES);
            }
            if (additions.size() > 0) {
                change.addAddition(makeN3InputStream(additions),
                        RDFService.ModelSerializationFormat.N3, ModelNames.ABOX_INFERENCES);
            }
            rdfService.changeSetUpdate(change);
            log.debug((System.currentTimeMillis() - start) +
                    " ms to retract " + retractions.size() +
                    " statements and add " + additions.size() + " statements");
        }
    }

    private InputStream makeN3InputStream(Model m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.write(out, "N3");
        return new ByteArrayInputStream(out.toByteArray());
    }

    private Set<String> getSameAsIndividuals(String individualURI) {
        HashSet<String> sameAsInds = new HashSet<String>();
        sameAsInds.add(individualURI);
        getSameAsIndividuals(individualURI, sameAsInds);
        sameAsInds.remove(individualURI);
        return sameAsInds;
    }

    private void getSameAsIndividuals(String individualUri, final Set<String> sameAsInds) {
        try {
            final List<String> addedURIs = new ArrayList<String>();
            StringBuilder builder = new StringBuilder();

            builder.append("SELECT\n")
                    .append("   ?object\n")
                    .append("WHERE\n")
                    .append("{\n")
                    .append("   <" + individualUri + "> <" + OWL.sameAs + "> ?object .\n")
                    .append("}\n");

            rdfService.sparqlSelectQuery(builder.toString(), new ResultSetConsumer() {
                @Override
                protected void processQuerySolution(QuerySolution qs) {
                    Resource object = qs.getResource("object");
                    if (object != null && !sameAsInds.contains(object.getURI())) {
                        sameAsInds.add(object.getURI());
                        addedURIs.add(object.getURI());
                    }
                }
            });

            for (String indUri : addedURIs) {
                getSameAsIndividuals(indUri, sameAsInds);
            }

            addedURIs.clear();
            builder = new StringBuilder();

            builder.append("SELECT\n")
                    .append("   ?subject\n")
                    .append("WHERE\n")
                    .append("{\n")
                    .append("   ?subject <" + OWL.sameAs + "> <" + individualUri + "> .\n")
                    .append("}\n");

            rdfService.sparqlSelectQuery(builder.toString(), new ResultSetConsumer() {
                @Override
                protected void processQuerySolution(QuerySolution qs) {
                    Resource object = qs.getResource("subject");
                    if (object != null && !sameAsInds.contains(object.getURI())) {
                        sameAsInds.add(object.getURI());
                        addedURIs.add(object.getURI());
                    }
                }
            });

            for (String indUri : addedURIs) {
                getSameAsIndividuals(indUri, sameAsInds);
            }
        } catch (RDFServiceException e) {

        }
    }

    /**
     * This is called when the application shuts down.
     */
    public void setStopRequested() {
        this.stopRequested = true;
    }

    /**
     * Caches for types -> inferred types, and types -> most specific type
     */
    private static class TypeCaches {
        private Map<TypeList, TypeList> inferredTypes     = new HashMap<TypeList, TypeList>();
        private Map<TypeList, TypeList> mostSpecificTypes = new HashMap<TypeList, TypeList>();

        void cacheInferredTypes(TypeList key, Model model) {
            inferredTypes.put(key, new TypeList(model, RDF.type));
        }

        Model getInferredTypesToModel(TypeList key, Resource individual) {
            TypeList types = inferredTypes.get(key);
            if (types != null) {
                return types.constructModel(individual, RDF.type);
            }
            return null;
        }

        void cacheMostSpecificTypes(TypeList key, Model model) {
            mostSpecificTypes.put(key, new TypeList(model, model.createProperty(VitroVocabulary.MOST_SPECIFIC_TYPE)));
        }

        Model getMostSpecificTypesToModel(TypeList key, Resource individual) {
            TypeList types = mostSpecificTypes.get(key);
            if (types != null) {
                return types.constructModel(individual, VitroVocabulary.MOST_SPECIFIC_TYPE);
            }
            return null;
        }
    }

    /**
     * Bundle of type URIs
     */
    private static class TypeList {
        private List<String> typeUris = new ArrayList<String>();

        private Integer hashCode = null;

        /**
         * Extract type uris - either RDF type or most specific type - from a Model
         */
        TypeList(Model model, Property property) {
            NodeIterator iterator = model.listObjectsOfProperty(property);
            while (iterator.hasNext()) {
                RDFNode node = iterator.next();
                String uri = node.asResource().getURI();
                if (!typeUris.contains(uri)) {
                    typeUris.add(uri);
                }
            }
        }

        Model constructModel(Resource individual, Property property) {
            Model model = ModelFactory.createDefaultModel();
            for (String uri : typeUris) {
                model.add(individual, property, model.createResource(uri));
            }

            return model;
        }

        Model constructModel(Resource individual, String property) {
            Model model = ModelFactory.createDefaultModel();
            for (String uri : typeUris) {
                model.add(individual, model.createProperty(property), model.createResource(uri));
            }

            return model;
        }

        public void addUri(String uri) {
            if (!typeUris.contains(uri)) {
                typeUris.add(uri);
                hashCode = null;
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof TypeList)) {
                return false;
            }

            TypeList otherKey = (TypeList)obj;

            if (typeUris.size() != otherKey.typeUris.size()) {
                return false;
            }

            return typeUris.containsAll(otherKey.typeUris);
        }

        @Override
        public int hashCode() {
            if (hashCode == null) {
                Collections.sort(typeUris);
                StringBuilder builder = new StringBuilder();
                for (String key : typeUris) {
                    builder.append('<').append(key).append('>');
                }

                hashCode = builder.toString().hashCode();
            }

            return hashCode;
        }
    }
}
