package org.orbisgis.noisemap.core.jdbc;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import org.h2gis.h2spatialapi.ProgressVisitor;
import org.h2gis.utilities.SFSUtilities;
import org.h2gis.utilities.SpatialResultSet;
import org.h2gis.utilities.TableLocation;
import org.orbisgis.noisemap.core.GeoWithSoilType;
import org.orbisgis.noisemap.core.MeshBuilder;
import org.orbisgis.noisemap.core.QueryGeometryStructure;
import org.orbisgis.noisemap.core.ThreadPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Common attributes for propagation of sound sources.
 * @author Nicolas Fortin
 */
public class JdbcNoiseMap {
    // When computing cell size, try to keep propagation distance away from the cell
    // inferior to this ratio (in comparison with cell width)
    protected static final double MINIMAL_BUFFER_RATIO = 0.3;
    protected final String buildingsTableName;
    protected final String sourcesTableName;
    protected String soilTableName = "";
    // Digital elevation model table. (Contains points or triangles)
    protected String demTable = "";
    protected String sound_lvl_field = "DB_M";
    protected double maximumPropagationDistance = 750;
    protected double maximumReflectionDistance = 400;
    protected int subdivisionLevel = -1; // TODO Guess it from maximumPropagationDistance and source extent
    protected int soundReflectionOrder = 2;
    protected int soundDiffractionOrder = 1;
    protected boolean computeVerticalDiffraction = true;
    protected double wallAbsorption = 0.05;
    protected String heightField = "";
    protected GeometryFactory geometryFactory = new GeometryFactory();
    protected boolean doMultiThreading = true;
    // Initialised attributes
    protected int gridDim = 0;
    protected Envelope mainEnvelope = new Envelope();
    protected List<Integer> db_field_ids = new ArrayList<>();
    protected List<Integer> db_field_freq = new ArrayList<>();

    public JdbcNoiseMap(String buildingsTableName, String sourcesTableName) {
        this.buildingsTableName = buildingsTableName;
        this.sourcesTableName = sourcesTableName;
    }

    /**
     * Compute the envelope corresping to parameters
     *
     * @param mainEnvelope Global envelope
     * @param cellI        I cell index
     * @param cellJ        J cell index
     * @param cellIMax     I cell count
     * @param cellJMax     J cell count
     * @param cellWidth    Cell width meter
     * @param cellHeight   Cell height meter
     * @return Envelope of the cell
     */
    public static Envelope getCellEnv(Envelope mainEnvelope, int cellI, int cellJ, double cellWidth,
                                      double cellHeight) {
        return new Envelope(mainEnvelope.getMinX() + cellI * cellWidth,
                mainEnvelope.getMinX() + cellI * cellWidth + cellWidth,
                mainEnvelope.getMinY() + cellHeight * cellJ,
                mainEnvelope.getMinY() + cellHeight * cellJ + cellHeight);
    }

    protected void fetchCellDem(Connection connection, Envelope fetchEnvelope, MeshBuilder mesh) throws SQLException {
        if(!demTable.isEmpty()) {
            String topoGeomName = SFSUtilities.getGeometryFields(connection,
                    TableLocation.parse(demTable)).get(0);
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT " + TableLocation.quoteIdentifier(topoGeomName) + " FROM " +
                            demTable + " WHERE " +
                            TableLocation.quoteIdentifier(topoGeomName) + " && ?")) {
                st.setObject(1, geometryFactory.toGeometry(fetchEnvelope));
                try (SpatialResultSet rs = st.executeQuery().unwrap(SpatialResultSet.class)) {
                    while (rs.next()) {
                        Geometry pt = rs.getGeometry();
                        if(pt != null) {
                            mesh.addTopographicPoint(pt.getCoordinate());
                        }
                    }
                }
            }
        }
    }

    protected void fetchCellSoilAreas(Connection connection, Envelope fetchEnvelope, List<GeoWithSoilType> geoWithSoil)
            throws SQLException {
        if(!soilTableName.isEmpty()){
            String soilGeomName = SFSUtilities.getGeometryFields(connection,
                    TableLocation.parse(soilTableName)).get(0);
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT " + TableLocation.quoteIdentifier(soilGeomName) + " FROM " +
                            soilTableName + " WHERE " +
                            TableLocation.quoteIdentifier(soilGeomName) + " && ?")) {
                st.setObject(1, geometryFactory.toGeometry(fetchEnvelope));
                try (SpatialResultSet rs = st.executeQuery().unwrap(SpatialResultSet.class)) {
                    while (rs.next()) {
                        Geometry poly = rs.getGeometry();
                        if(poly != null) {
                            geoWithSoil.add(new GeoWithSoilType(poly, rs.getDouble("G")));
                        }
                    }
                }
            }
        }
    }

    protected void fetchCellBuildings(Connection connection, Envelope fetchEnvelope, List<Geometry> buildingsGeometries,
                                      MeshBuilder mesh) throws SQLException {
        String queryHeight = "";
        if(!heightField.isEmpty()) {
            queryHeight = ", " + TableLocation.quoteIdentifier(heightField);
        }
        String buildingGeomName = SFSUtilities.getGeometryFields(connection,
                TableLocation.parse(buildingsTableName)).get(0);
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT " + TableLocation.quoteIdentifier(buildingGeomName) + queryHeight + " FROM " +
                        buildingsTableName + " WHERE " +
                        TableLocation.quoteIdentifier(buildingGeomName) + " && ?")) {
            st.setObject(1, geometryFactory.toGeometry(fetchEnvelope));
            try (SpatialResultSet rs = st.executeQuery().unwrap(SpatialResultSet.class)) {
                while (rs.next()) {
                    //if we don't have height of building
                    Geometry building = rs.getGeometry();
                    if(building != null) {
                        buildingsGeometries.add(building);
                        if (heightField.isEmpty()) {
                            mesh.addGeometry(building);
                        } else {
                            mesh.addGeometry(building, rs.getDouble(heightField));
                        }
                    }
                }
            }
        }
    }


    protected void fetchCellSource(Connection connection,Envelope fetchEnvelope, List<Geometry> sourceGeometries,
                                   List<ArrayList<Double>> wj_sources, QueryGeometryStructure sourcesIndex)
            throws SQLException {
        int idSource = 0;
        TableLocation sourceTableIdentifier = TableLocation.parse(sourcesTableName);
        String sourceGeomName = SFSUtilities.getGeometryFields(connection, sourceTableIdentifier).get(0);
        try (PreparedStatement st = connection.prepareStatement("SELECT * FROM " + sourcesTableName + " WHERE "
                + TableLocation.quoteIdentifier(sourceGeomName) + " && ?")) {
            st.setObject(1, geometryFactory.toGeometry(fetchEnvelope));
            try (SpatialResultSet rs = st.executeQuery().unwrap(SpatialResultSet.class)) {
                while (rs.next()) {
                    Geometry geo = rs.getGeometry();
                    if (geo != null) {
                        sourcesIndex.appendGeometry(geo, idSource);
                        ArrayList<Double> wj_spectrum = new ArrayList<>();
                        wj_spectrum.ensureCapacity(db_field_ids.size());
                        for (Integer idcol : db_field_ids) {
                            wj_spectrum
                                    .add(DbaToW(rs.getDouble(idcol)));
                        }
                        wj_sources.add(wj_spectrum);
                        sourceGeometries.add(geo);
                        idSource++;
                    }
                }
            }
        }

    }


    protected double getCellWidth() {
        return mainEnvelope.getWidth() / gridDim;
    }
    protected double getCellHeight() {
        return mainEnvelope.getHeight() / gridDim;
    }

    protected static Double DbaToW(Double dBA) {
        return Math.pow(10., dBA / 10.);
    }
    /**
     * Fetch scene attributes, compute best computation cell size.
     * @param connection Active connection
     * @throws java.sql.SQLException
     */
    public void initialize(Connection connection, ProgressVisitor progression) throws SQLException {
        if(maximumPropagationDistance < maximumReflectionDistance) {
            throw new SQLException(new IllegalArgumentException(
                    "Maximum wall seeking distance cannot be superior than maximum propagation distance"));
        }
        if(sourcesTableName.isEmpty()) {
            throw new SQLException("A sound source table must be provided");
        }
        ThreadPool threadManager = null;
        // Steps of execution
        // Evaluation of the main bounding box (sourcesTableName+buildingsTableName)
        // Split domain into 4^subdiv cells
        // For each cell :
        // Expand bounding box cell by maxSrcDist
        // Build delaunay triangulation from buildingsTableName polygon processed by
        // intersection with non extended bounding box
        // Save the list of sourcesTableName index inside the extended bounding box
        // Save the list of buildingsTableName index inside the extended bounding box
        // Make a structure to keep the following information
        // Triangle list with the 3 vertices index
        // Vertices list (as receivers)
        // For each vertices within the cell bounding box (not the extended
        // one)
        // Find all sourcesTableName within maxSrcDist
        // For All found sourcesTableName
        // Test if there is a gap(no building) between source and receiver
        // if not then append the distance attenuated sound level to the
        // receiver
        // Save the triangle geometry with the db_m value of the 3 vertices
        // 1 Step - Evaluation of the main bounding box (sources)
        setMainEnvelope(SFSUtilities.getTableEnvelope(connection, TableLocation.parse(sourcesTableName), ""));

        // Initialization frequency declared in source Table
        db_field_ids = new ArrayList<>();
        db_field_freq = new ArrayList<>();
        TableLocation sourceTableIdentifier = TableLocation.parse(sourcesTableName);
        try(ResultSet rs = connection.getMetaData().getColumns(sourceTableIdentifier.getCatalog(),
                sourceTableIdentifier.getSchema(), sourceTableIdentifier.getTable(), null)) {
            while(rs.next()) {
                String fieldName = rs.getString("COLUMN_NAME");
                if (fieldName.startsWith(sound_lvl_field)) {
                    String sub = fieldName.substring(sound_lvl_field.length());
                    db_field_ids.add(rs.getInt("ORDINAL_POSITION"));
                    if (sub.length() > 0) {
                        int freq = Integer.parseInt(sub);
                        db_field_freq.add(freq);
                    } else {
                        db_field_freq.add(0);
                    }
                }
            }
        }
    }

    /**
     * @return Side computation cell count (same on X and Y)
     */
    public int getGridDim() {
        return gridDim;
    }

    /**
     * This table must contain a POLYGON column, where Z values are wall bottom position relative to sea level.
     * It may also contain a height field (0-N] average building height from the ground.
     * @return Table name that contains buildings
     */
    public String getBuildingsTableName() {
        return buildingsTableName;
    }

    /**
     * This table must contain a POINT or LINESTRING column, and spectrum in dB(A).
     * Spectrum column name must be {@link #sound_lvl_field}HERTZ. Where HERTZ is a number [100-5000]
     * @return Table name that contain linear and/or punctual sound sources.     *
     */
    public String getSourcesTableName() {
        return sourcesTableName;
    }

    /**
     * Extracted from NMPB 2008-2 7.3.2
     * Soil areas POLYGON, with a dimensionless coefficient G:
     *  - Law, meadow, field of cereals G=1
     *  - Undergrowth (resinous or decidious) G=1
     *  - Compacted earth, track G=0.3
     *  - Road surface G=0
     *  - Smooth concrete G=0
     * @return Table name of grounds properties
     */
    public String getSoilTableName() {
        return soilTableName;
    }


    /**
     * Extracted from NMPB 2008-2 7.3.2
     * Soil areas POLYGON, with a dimensionless coefficient G:
     *  - Law, meadow, field of cereals G=1
     *  - Undergrowth (resinous or decidious) G=1
     *  - Compacted earth, track G=0.3
     *  - Road surface G=0
     *  - Smooth concrete G=0
     * @param soilTableName Table name of grounds properties
     */
    public void setSoilTableName(String soilTableName) {
        this.soilTableName = soilTableName;
    }

    /**
     * Digital Elevation model table name. Currently only a table with POINTZ column is supported.
     * DEM points too close with buildings are not fetched.
     * @return Digital Elevation model table name
     */
    public String getDemTable() {
        return demTable;
    }

    /**
     * Digital Elevation model table name. Currently only a table with POINTZ column is supported.
     * DEM points too close with buildings are not fetched.
     * @param demTable Digital Elevation model table name
     */
    public void setDemTable(String demTable) {
        this.demTable = demTable;
    }

    /**
     * Field name of the {@link #sourcesTableName}HERTZ. Where HERTZ is a number [100-5000].
     * Without the hertz value.
     * @return Hertz field prefix
     */
    public String getSound_lvl_field() {
        return sound_lvl_field;
    }

    /**
     * Field name of the {@link #sourcesTableName}HERTZ. Where HERTZ is a number [100-5000].
     * Without the hertz value.
     * @param sound_lvl_field Hertz field prefix
     */
    public void setSound_lvl_field(String sound_lvl_field) {
        this.sound_lvl_field = sound_lvl_field;
    }

    /**
     * @return Sound propagation stop at this distance, default to 750m.
     * Computation cell size if proportional with this value.
     */
    public double getMaximumPropagationDistance() {
        return maximumPropagationDistance;
    }

    /**
     * @param maximumPropagationDistance  Sound propagation stop at this distance, default to 750m.
     * Computation cell size if proportional with this value.
     */
    public void setMaximumPropagationDistance(double maximumPropagationDistance) {
        this.maximumPropagationDistance = maximumPropagationDistance;
    }

    /**
     * @return Reflection and diffraction maximum search distance, default to 400m.
     */
    public double getMaximumReflectionDistance() {
        return maximumReflectionDistance;
    }

    /**
     * @param maximumReflectionDistance Reflection and diffraction maximum search distance, default to 400m.
     */
    public void setMaximumReflectionDistance(double maximumReflectionDistance) {
        this.maximumReflectionDistance = maximumReflectionDistance;
    }

    /**
     * @return Subdivision of {@link #mainEnvelope}. This is a quadtree subdivision in the for 4^N
     */
    public int getSubdivisionLevel() {
        return subdivisionLevel;
    }

    /**
     * @param subdivisionLevel Subdivision of {@link #mainEnvelope}. This is a quadtree subdivision in the for 4^N
     */
    public void setSubdivisionLevel(int subdivisionLevel) {
        this.subdivisionLevel = subdivisionLevel;
    }

    /**
     * @return Sound reflection order. 0 order mean 0 reflection depth.
     * 2 means propagation of rays up to 2 collision with walls.
     */
    public int getSoundReflectionOrder() {
        return soundReflectionOrder;
    }

    /**
     * @param soundReflectionOrder Sound reflection order. 0 order mean 0 reflection depth.
     * 2 means propagation of rays up to 2 collision with walls.
     */
    public void setSoundReflectionOrder(int soundReflectionOrder) {
        this.soundReflectionOrder = soundReflectionOrder;
    }

    /**
     * @return Sound vertical diffraction order. 0 order mean 0 diffraction depth.
     * 2 means propagation of rays up to 2 corners.
     */
    public int getSoundDiffractionOrder() {
        return soundDiffractionOrder;
    }

    /**
     * @param soundDiffractionOrder Sound vertical diffraction order. 0 order mean 0 diffraction depth.
     * 2 means propagation of rays up to 2 corners.
     */
    public void setSoundDiffractionOrder(int soundDiffractionOrder) {
        this.soundDiffractionOrder = soundDiffractionOrder;
    }

    /**
     * @return Global wall absorption on sound reflection.
     */
    public double getWallAbsorption() {
        return wallAbsorption;
    }

    /**
     * @param wallAbsorption Global wall absorption on sound reflection.
     */
    public void setWallAbsorption(double wallAbsorption) {
        this.wallAbsorption = wallAbsorption;
    }

    /**
     * @return {@link #buildingsTableName} table field name for buildings height above the ground.
     */
    public String getHeightField() {
        return heightField;
    }

    /**
     * @param heightField {@link #buildingsTableName} table field name for buildings height above the ground.
     */
    public void setHeightField(String heightField) {
        this.heightField = heightField;
    }

    /**
     * @return True if multi-threading is activated.
     */
    public boolean isDoMultiThreading() {
        return doMultiThreading;
    }

    /**
     * @param doMultiThreading True to use all available cores.
     */
    public void setDoMultiThreading(boolean doMultiThreading) {
        this.doMultiThreading = doMultiThreading;
    }

    /**
     * @return The envelope of computation area.
     */
    public Envelope getMainEnvelope() {
        return mainEnvelope;
    }

    /**
     * Set computation area. Update the property subdivisionLevel and gridDim.
     * @param mainEnvelope Computation area
     */
    public void setMainEnvelope(Envelope mainEnvelope) {
        this.mainEnvelope = mainEnvelope;
        // Split domain into 4^subdiv cells
        // Compute subdivision level using envelope and maximum propagation distance
        double greatestSideLength = mainEnvelope.maxExtent();
        subdivisionLevel = 0;
        while(maximumPropagationDistance / (greatestSideLength / Math.pow(2, subdivisionLevel)) < MINIMAL_BUFFER_RATIO) {
            subdivisionLevel++;
        }
        gridDim = (int) Math.pow(2, subdivisionLevel);
    }

    public boolean isComputeVerticalDiffraction() {
        return computeVerticalDiffraction;
    }

    public void setComputeVerticalDiffraction(boolean computeVerticalDiffraction) {
        this.computeVerticalDiffraction = computeVerticalDiffraction;
    }
}
