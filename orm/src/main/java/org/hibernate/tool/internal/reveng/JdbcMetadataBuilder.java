/*
 * Created on 2004-11-23
 *
 */
package org.hibernate.tool.internal.reveng;


import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.hibernate.DuplicateMappingException;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.internal.BootstrapContextImpl;
import org.hibernate.boot.internal.InFlightMetadataCollectorImpl;
import org.hibernate.boot.internal.MetadataBuilderImpl.MetadataBuildingOptionsImpl;
import org.hibernate.boot.internal.MetadataBuildingContextRootImpl;
import org.hibernate.boot.internal.MetadataImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.OneToOne;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.RootClass;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.mapping.Table;
import org.hibernate.tool.api.dialect.MetaDataDialect;
import org.hibernate.tool.api.dialect.MetaDataDialectFactory;
import org.hibernate.tool.api.metadata.MetadataDescriptor;
import org.hibernate.tool.api.reveng.DatabaseCollector;
import org.hibernate.tool.api.reveng.ReverseEngineeringStrategy;
import org.hibernate.tool.api.reveng.TableIdentifier;
import org.hibernate.tool.internal.reveng.binder.BasicPropertyBinder;
import org.hibernate.tool.internal.reveng.binder.BinderUtils;
import org.hibernate.tool.internal.reveng.binder.ForeignKeyUtils;
import org.hibernate.tool.internal.reveng.binder.ManyToOneBinder;
import org.hibernate.tool.internal.reveng.binder.OneToManyBinder;
import org.hibernate.tool.internal.reveng.binder.OneToOneBinder;
import org.hibernate.tool.internal.reveng.binder.PrimaryKeyBinder;
import org.hibernate.tool.internal.reveng.binder.VersionPropertyBinder;
import org.jboss.logging.Logger;


/**
 * @author max
 * @author koen
 */
public class JdbcMetadataBuilder {
	
	
	public static JdbcMetadataBuilder create(
			Properties properties, 
			ReverseEngineeringStrategy reverseEngineeringStrategy) {
		return new JdbcMetadataBuilder(properties, reverseEngineeringStrategy);
	}
	
	private static final Logger log = Logger.getLogger(JdbcMetadataBuilder.class);

	private final Properties properties;
	private final MetadataBuildingContext metadataBuildingContext;	
	private final InFlightMetadataCollectorImpl metadataCollector;	
	private final ReverseEngineeringStrategy revengStrategy;
	
	private boolean preferBasicCompositeIds;
	private final StandardServiceRegistry serviceRegistry;
	private final String defaultCatalog;
	private final String defaultSchema;
	
	private JdbcMetadataBuilder(
			Properties properties,
			ReverseEngineeringStrategy reverseEngineeringStrategy) {
		this.properties = properties;
		this.revengStrategy = reverseEngineeringStrategy;
		this.serviceRegistry = new StandardServiceRegistryBuilder()
				.applySettings(properties)
				.build();
		MetadataBuildingOptionsImpl metadataBuildingOptions = 
				new MetadataBuildingOptionsImpl(serviceRegistry);	
		BootstrapContextImpl bootstrapContext = new BootstrapContextImpl(
				serviceRegistry, 
				metadataBuildingOptions);
		metadataBuildingOptions.setBootstrapContext(bootstrapContext);
		this.metadataCollector = 
				new InFlightMetadataCollectorImpl(
						bootstrapContext,
						metadataBuildingOptions);
		this.metadataBuildingContext = new MetadataBuildingContextRootImpl(bootstrapContext, metadataBuildingOptions, metadataCollector);
		this.preferBasicCompositeIds = (Boolean)properties.get(MetadataDescriptor.PREFER_BASIC_COMPOSITE_IDS);
		this.defaultCatalog = properties.getProperty(AvailableSettings.DEFAULT_CATALOG);
		this.defaultSchema = properties.getProperty(AvailableSettings.DEFAULT_SCHEMA);
	}

	public Metadata build() {
		Metadata result = createMetadata();		
	    DatabaseCollector collector = readFromDatabase();
        createPersistentClasses(collector, result); //move this to a different step!
		return result;
	}
	
	private MetadataImpl createMetadata() {
		MetadataImpl result = metadataCollector.buildMetadataInstance(metadataBuildingContext);
		result.getTypeConfiguration().scope(metadataBuildingContext);		
		return result;
	}
	
	private DatabaseCollector readFromDatabase() {
		MetaDataDialect mdd = MetaDataDialectFactory
				.createMetaDataDialect(
						serviceRegistry.getService(JdbcServices.class).getDialect(), 
						properties );
	    JDBCReader reader = JDBCReader.create(properties,revengStrategy,mdd, serviceRegistry);
	    DatabaseCollector collector = new MappingsDatabaseCollector(metadataCollector, reader.getMetaDataDialect());
        reader.readDatabaseSchema(collector, defaultCatalog, defaultSchema);
        return collector;
	}
	
	/**
	 * @param manyToOneCandidates
	 * @param mappings2
	 */
	private void createPersistentClasses(DatabaseCollector collector, Metadata metadata) {
		BinderMapping mapping = new BinderMapping(metadata);
		Map<String, List<ForeignKey>> manyToOneCandidates = collector.getOneToManyCandidates();
		for (Iterator<Table> iter = metadataCollector.collectTableMappings().iterator(); iter.hasNext();) {
			Table table = iter.next();
			if (table.getCatalog() != null && table.getCatalog().equals(defaultCatalog)) {
				table.setCatalog(null);
			}
			if (table.getSchema() != null && table.getSchema().equals(defaultSchema)) {
				table.setSchema(null);
			}
			if(table.getColumnSpan()==0) {
				log.warn("Cannot create persistent class for " + table + " as no columns were found.");
				continue;
			}
			// TODO: this naively just create an entity per table
			// should have an opt-out option to mark some as helper tables, subclasses etc.
			/*if(table.getPrimaryKey()==null || table.getPrimaryKey().getColumnSpan()==0) {
			    log.warn("Cannot create persistent class for " + table + " as no primary key was found.");
                continue;
                // TODO: just create one big embedded composite id instead.
            }*/

			if(revengStrategy.isManyToManyTable(table)) {
				log.debug( "Ignoring " + table + " as class since rev.eng. says it is a many-to-many" );
				continue;
			}

	    	
			RootClass rc = new RootClass(metadataBuildingContext);
			TableIdentifier tableIdentifier = TableIdentifier.create(table);
			String className = revengStrategy.tableToClassName( tableIdentifier );
			log.debug("Building entity " + className + " based on " + tableIdentifier);
			rc.setEntityName( className );
			rc.setJpaEntityName( StringHelper.unqualify( className ) );
			rc.setClassName( className );
			rc.setProxyInterfaceName( rc.getEntityName() ); // TODO: configurable ?
			rc.setLazy(true);

			rc.setMetaAttributes(
					BinderUtils.safeMap(
							RevEngUtils.getTableToMetaAttributesInRevengStrategy(
									revengStrategy, 
									table, 
									defaultCatalog, 
									defaultSchema)));


			rc.setDiscriminatorValue( rc.getEntityName() );
			rc.setTable(table);
			try {
				metadataCollector.addEntityBinding(rc);
			} catch(DuplicateMappingException dme) {
				// TODO: detect this and generate a "permutation" of it ?
				PersistentClass class1 = metadataCollector.getEntityBinding(dme.getName());
				Table table2 = class1.getTable();
				throw new RuntimeException("Duplicate class name '" + rc.getEntityName() + "' generated for '" + table + "'. Same name where generated for '" + table2 + "'");
			}
			metadataCollector.addImport( rc.getEntityName(), rc.getEntityName() );

			Set<Column> processed = new HashSet<Column>();

			PrimaryKeyInfo pki = PrimaryKeyBinder
					.create(
							metadataBuildingContext, 
							metadataCollector, 
							revengStrategy, 
							defaultCatalog, 
							defaultSchema, 
							preferBasicCompositeIds)
					.bind(
							table, 
							rc, 
							processed, 
							mapping, 
							collector);
			
			VersionPropertyBinder
				.create(
						metadataBuildingContext, 
						metadataCollector, 
						revengStrategy, 
						defaultCatalog, 
						defaultSchema)
				.bind(
						table, 
						rc, 
						processed, 
						mapping);
			
			bindOutgoingForeignKeys(table, rc, processed);
			bindColumnsToProperties(table, rc, processed, mapping);
			List<ForeignKey> incomingForeignKeys = manyToOneCandidates.get( rc.getEntityName() );
			bindIncomingForeignKeys(rc, processed, incomingForeignKeys, mapping);
			updatePrimaryKey(rc, pki);

		}
		
		metadataCollector.processSecondPasses(metadataBuildingContext);		
	}

	private void updatePrimaryKey(RootClass rc, PrimaryKeyInfo pki) {
		SimpleValue idValue = (SimpleValue) rc.getIdentifierProperty().getValue();

		Properties defaultStrategyProperties = new Properties();
		Property constrainedOneToOne = getConstrainedOneToOne(rc);
		if(constrainedOneToOne!=null) {
			if(pki.suggestedStrategy==null) {
				idValue.setIdentifierGeneratorStrategy("foreign");
			}

			if(pki.suggestedProperties==null) {
				defaultStrategyProperties.setProperty("property", constrainedOneToOne.getName());
				idValue.setIdentifierGeneratorProperties(defaultStrategyProperties);
			}
		}



	}

	private Property getConstrainedOneToOne(RootClass rc) {
		Iterator<?> propertyClosureIterator = rc.getPropertyClosureIterator();
		while (propertyClosureIterator.hasNext()) {
			Property property = (Property) propertyClosureIterator.next();
			if(property.getValue() instanceof OneToOne) {
				OneToOne oto = (OneToOne) property.getValue();
				if(oto.isConstrained()) {
					return property;
				}
			}
		}
		return null;
	}

	// bind collections.
	@SuppressWarnings("unchecked")
	private void bindIncomingForeignKeys(PersistentClass rc, Set<Column> processed, List<ForeignKey> foreignKeys, Mapping mapping) {
		if(foreignKeys!=null) {
			for (Iterator<ForeignKey> iter = foreignKeys.iterator(); iter.hasNext();) {
				ForeignKey foreignKey = iter.next();

				if(revengStrategy.excludeForeignKeyAsCollection(
						foreignKey.getName(),
						TableIdentifier.create(foreignKey.getTable() ),
						foreignKey.getColumns(),
						TableIdentifier.create(foreignKey.getReferencedTable() ),
						foreignKey.getReferencedColumns())) {
					log.debug("Rev.eng excluded one-to-many or one-to-one for foreignkey " + foreignKey.getName());
				} else if (revengStrategy.isOneToOne(foreignKey)){
	            	Property property = OneToOneBinder
	            			.create(
	            					metadataBuildingContext, 
	            					revengStrategy, 
	            					defaultCatalog, 
	            					defaultSchema)
	            			.bind(
	            					rc, 
	            					foreignKey.getTable(), 
	            					foreignKey, 
	            					processed, 
	            					false, 
	            					true);
					rc.addProperty(property);
				} else {
					Property property = OneToManyBinder
							.create(
									metadataBuildingContext, 
									metadataCollector, 
									revengStrategy, 
									defaultCatalog, 
									defaultSchema)
							.bind(
									rc, 
									foreignKey, 
									processed, 
									mapping);
					rc.addProperty(property);
				}
			}
		}
	}



	private void bindOutgoingForeignKeys(Table table, RootClass rc, Set<Column> processedColumns) {

		// Iterate the outgoing foreign keys and create many-to-one's
		for(Iterator<?> iterator = table.getForeignKeyIterator(); iterator.hasNext();) {
			ForeignKey foreignKey = (ForeignKey) iterator.next();

			boolean mutable = true;
            if ( contains( foreignKey.getColumnIterator(), processedColumns ) ) {
				if ( !preferBasicCompositeIds ) continue; //it's in the pk, so skip this one
				mutable = false;
            }

            if(revengStrategy.excludeForeignKeyAsManytoOne(foreignKey.getName(),
        			TableIdentifier.create(foreignKey.getTable() ),
        			foreignKey.getColumns(),
        			TableIdentifier.create(foreignKey.getReferencedTable() ),
        			foreignKey.getReferencedColumns())) {
            	// TODO: if many-to-one is excluded should the column be marked as processed so it won't show up at all ?
            	log.debug("Rev.eng excluded *-to-one for foreignkey " + foreignKey.getName());
            } else if (revengStrategy.isOneToOne(foreignKey)){
            	Property property = OneToOneBinder
            			.create(
            					metadataBuildingContext, 
            					revengStrategy, 
            					defaultCatalog, 
            					defaultSchema)
            			.bind(
            					rc, 
            					foreignKey.getReferencedTable(), 
            					foreignKey, 
            					processedColumns, 
            					true, 
            					false);
				rc.addProperty(property);
			} else {
            	boolean isUnique = ForeignKeyUtils.isUniqueReference(foreignKey);
            	String propertyName = revengStrategy.foreignKeyToEntityName(
            			foreignKey.getName(),
            			TableIdentifier.create(foreignKey.getTable() ),
            			foreignKey.getColumns(),
            			TableIdentifier.create(foreignKey.getReferencedTable() ),
            			foreignKey.getReferencedColumns(),
            			isUnique
            	);

            	Property property = ManyToOneBinder
            			.create(
            					metadataBuildingContext, 
            					revengStrategy, 
            					defaultCatalog, 
            					defaultSchema)
            			.bind(
            					BinderUtils.makeUnique(rc, propertyName), 
            					mutable, 
            					table, 
            					foreignKey, 
            					processedColumns);

            	rc.addProperty(property);
            }
		}
	}

	/**
	 * @param table
	 * @param rc
	 * @param primaryKey
	 */
	private void bindColumnsToProperties(Table table, RootClass rc, Set<Column> processedColumns, Mapping mapping) {
		for (Iterator<?> iterator = table.getColumnIterator(); iterator.hasNext();) {
			Column column = (Column) iterator.next();
			if ( !processedColumns.contains(column) ) {
				BinderUtils.checkColumnForMultipleBinding(column);
				String propertyName = 
						RevEngUtils.getColumnToPropertyNameInRevengStrategy(
								revengStrategy, 
								table, 
								defaultCatalog, 
								defaultSchema, 
								column.getName());				
				Property property = BasicPropertyBinder
						.create(
								metadataBuildingContext, 
								metadataCollector, 
								revengStrategy, 
								defaultCatalog, 
								defaultCatalog)
						.bind(BinderUtils.makeUnique(rc,propertyName), table, column, mapping);
				rc.addProperty(property);
			}
		}
	}

    /**
     * @param columnIterator
     * @param processedColumns
     * @return
     */
    private boolean contains(Iterator<Column> columnIterator, Set<Column> processedColumns) {
        while (columnIterator.hasNext() ) {
            Column element = (Column) columnIterator.next();
            if(processedColumns.contains(element) ) {
                return true;
            }
        }
        return false;
    }

 }