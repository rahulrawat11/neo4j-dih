package org.neo4j.dih.service;

import generated.DataConfig;
import generated.DataSourceType;
import generated.EntityType;
import generated.GraphType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.neo4j.dih.bean.ScriptStatistics;
import org.neo4j.dih.datasource.AbstractDataSource;
import org.neo4j.dih.datasource.AbstractResultList;
import org.neo4j.dih.datasource.file.csv.CSVDataSource;
import org.neo4j.dih.datasource.file.json.JSONDataSource;
import org.neo4j.dih.datasource.file.xml.XMLDataSource;
import org.neo4j.dih.datasource.jdbc.JDBCDataSource;
import org.neo4j.dih.exception.DIHException;
import org.neo4j.dih.exception.DIHRuntimeException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Service that do the import job.
 *
 * @author bsimard
 * @version $Id: $Id
 */
public class ImporterService {

    /**
     * The logger
     */
    private static final Logger log = LoggerFactory.getLogger(ImporterService.class);

    /**
     * The graph database instance.
     */
    private GraphDatabaseService graphDb;

    /**
     * Property service.
     */
    private ImporterPropertiesService properties;

    /**
     * Is in debug mode ?
     * If debug mode is activated, we don't make any write operation.
     */
    private Boolean debug = Boolean.FALSE;

    /**
     * Is in clean mode ?
     * If clean mode is activated, we delete all the database into a separate transaction before importing.
     */
    private Boolean clean = Boolean.FALSE;

    /**
     * The XML config.
     */
    private DataConfig config;

    /**
     * Map of available datasource.
     */
    private Map<String, AbstractDataSource> dataSources;

    /**
     * The cypher generated script.
     */
    private String script = "";

    /**
     * Current number of iteration.
     * This param is used for <code>periodicCommit</code>
     */
    private Integer iteration = 0;

    /**
     * Current status of periodic commit (for the current <code>graph</code> import).
     * By default, it's null, so there is no periodic commit.
     */
    private Integer periodicCommit;

    /**
     * Some statistic on the script execution.
     */
    public ScriptStatistics stats;

    /**
     * Method that find all configuration files.
     *
     * @return a {@link java.util.List} object.
     */
    public static List<String> getAllConfiguration() {
        List<String> result = new ArrayList();

        String path = ClassLoader.getSystemResource("conf/dih/").getFile();
        Collection<File> files = FileUtils.listFiles(new File(path), new String[]{"xml"}, true);
        for(File file : files) {
            result.add(file.getAbsolutePath().replace(path, ""));
        }
        return result;
    }

    /**
     * Constructor.
     *
     * @param graphDb a {@link org.neo4j.graphdb.GraphDatabaseService} object.
     * @param filename a {@link java.lang.String} object.
     * @param clean a {@link java.lang.Boolean} object.
     * @param debug a {@link java.lang.Boolean} object.
     * @throws org.neo4j.dih.exception.DIHException if any.
     */
    public ImporterService(GraphDatabaseService graphDb, String filename, Boolean clean, Boolean debug) throws DIHException {


        // Init services
        this.graphDb = graphDb;
        this.properties = new ImporterPropertiesService(filename);

        // Init config  & datasource
        XmlParserService parser = new XmlParserService();
        this.config = parser.execute(filename);
        this.dataSources = retrieveDataSources();

        if (clean != null)
            this.clean = clean;
        if (debug != null)
            this.debug = debug;

        this.stats = new ScriptStatistics();
    }

    /**
     * Execute the import.
     *
     * @throws org.neo4j.dih.exception.DIHException if any.
     */
    public void execute() throws DIHException {

        // saving the starting date
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        String date = sdf.format(new Date());

        // retrieve properties of the job
        Map<String, Object> props = properties.readPropertiesAsMap();
        props.put("debug", this.debug);
        props.put("clean", this.clean);

        // Process the cleaning mod if needed.
        if(!debug) {
            clean();
        }

        // Starting the job import
        starting();

        for (GraphType graph : config.getGraph()) {

            // Init var for the graph import
            script = "";
            iteration = 0;
            periodicCommit = null;

            // If there is a periodic commit
            if (graph.getPeriodicCommit() != null) {
                periodicCommit = graph.getPeriodicCommit().intValue();
            }

            // Let's process the config XML recursively
            Map<String, Object> state = new HashMap<>();
            state.putAll(props);
            List<Object> listEntityOrCypher = graph.getEntityOrCypher();
            process(listEntityOrCypher, state);

            // Execute the cypher script if we are not in debug mode
            if (!debug) {
                cypher(script);
                properties.setProperty(ImporterPropertiesService.LAST_INDEX_TIME, date);
                properties.save();
            }
        }

        // Ending the import
        ending();
    }

    /**
     * Before starting the import, we initializing some stuff.
     *
     * @throws DIHException
     */
    private void starting() throws DIHException {
        for (Map.Entry<String, AbstractDataSource> entry : dataSources.entrySet()) {
            dataSources.get(entry.getKey()).start();
        }
    }

    /**
     * Before finishing the import, we closing some stuff.
     *
     * @throws DIHException
     */
    private void ending() throws DIHException {
        for (Map.Entry<String, AbstractDataSource> entry : dataSources.entrySet()) {
            dataSources.get(entry.getKey()).finish();
        }
    }

    /**
     * Execute the clean mode if needed.
     */
    protected void clean() {
        // If clean mode is enable
        if (clean) {
            String cleanQuery = "MATCH (n) OPTIONAL MATCH (n)-[r]-(m) DELETE n,r,m;";
            if (!StringUtils.isEmpty(config.getClean())) {
                cleanQuery = config.getClean();
            }
            cypher(cleanQuery);
        }
    }

    /**
     * Process recursively a list of entity or cypher.
     *
     * @param listEntityOrCypher a {@link java.util.List} object.
     * @param state a {@link java.util.Map} object.
     * @throws org.neo4j.dih.exception.DIHException if any.
     */
    protected void process(List<Object> listEntityOrCypher, Map<String, Object> state) throws DIHException {
        for (Object obj : listEntityOrCypher) {
            if (obj instanceof EntityType) {
                processEntity((EntityType) obj, state);
            } else {
                processCypher((String) obj, state);
            }
        }
    }

    /**
     * Process an Entity.
     *
     * @param entity a {@link generated.EntityType} object.
     * @throws org.neo4j.dih.exception.DIHException if any.
     * @param state a {@link java.util.Map} object.
     */
    protected void processEntity(EntityType entity, Map<String, Object> state) throws DIHException {
        AbstractDataSource dataSource = dataSources.get(entity.getDataSource());
        try (AbstractResultList result = dataSource.execute(entity, state)) {
            while (result.hasNext()) {
                state.put(entity.getName(), result.next());
                process(entity.getEntityOrCypher(), state);
            }
        } catch (IOException e) {
            throw new DIHException(e);
        }
    }

    /**
     * Process a Cypher query.
     *
     * @param cypher a {@link java.lang.String} object.
     * @param state a {@link java.util.Map} object.
     */
    protected void processCypher(String cypher, Map<String, Object> state) {
        iteration++;
        script += TemplateService.getInstance().compile(cypher, state);

        // If we are not in debug mode AND there is a periodic commit
        // => if the periodic commit is reached => we make a transaction !
        if (!debug && (periodicCommit != null) && ((iteration % periodicCommit) == 0)) {
            cypher(script);
            script = "";
        }
    }

    /**
     * Retrieve and construct a map of datasource by their name.
     *
     * @return a {@link java.util.Map} object.
     * @throws org.neo4j.dih.exception.DIHException if any.
     */
    protected Map<String, AbstractDataSource> retrieveDataSources() throws DIHException {
        Map<String, AbstractDataSource> dataSources = new HashMap();
        for (DataSourceType dsConfig : config.getDataSource()) {
            // but we need a unique package
            switch (dsConfig.getType()) {
                case "JDBCDataSource":
                    JDBCDataSource jdbcDataSource = new JDBCDataSource(dsConfig);
                    dataSources.put(dsConfig.getName(), jdbcDataSource);
                    break;
                case "CSVDataSource":
                    CSVDataSource csvDataSource = new CSVDataSource(dsConfig);
                    dataSources.put(dsConfig.getName(), csvDataSource);
                    break;
                case "XMLDataSource":
                    XMLDataSource xmlDataSource = new XMLDataSource(dsConfig);
                    dataSources.put(dsConfig.getName(), xmlDataSource);
                    break;
                case "JSONDataSource":
                    JSONDataSource jsonDataSource = new JSONDataSource(dsConfig);
                    dataSources.put(dsConfig.getName(), jsonDataSource);
                    break;
                default:
                    try {
                        Class c = Class.forName("org.neo4j.dih.datasource." + dsConfig.getType());
                        Constructor constructor = c.getConstructor(DataSourceType.class);
                        AbstractDataSource dataSource = (AbstractDataSource) constructor.newInstance((dsConfig));
                        dataSources.put(dsConfig.getName(), dataSource);
                    } catch (ClassNotFoundException e) {
                        throw new DIHRuntimeException("Type %s on datasource is mandatory and must exist", dsConfig.getType());
                    } catch (NoSuchMethodException e) {
                        throw new DIHRuntimeException("Datasource %s must implement AbstractDataSource", dsConfig.getType());
                    } catch (Exception e) {
                        throw new DIHRuntimeException(e);
                    }

            }
        }
        return dataSources;
    }

    /**
     * Execute a Cypher query.
     *
     * @param script a {@link java.lang.String} object.
     * @return a {@link org.neo4j.graphdb.Result} object.
     */
    protected Result cypher(String script) {
        Result rs = null;

        if (!StringUtils.isEmpty(script)) {
            try (Transaction tx = graphDb.beginTx()) {
                rs = graphDb.execute(script);
                tx.success();

                this.stats.add(rs.getQueryStatistics());
            }
        }
        return rs;
    }

}
