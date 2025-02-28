/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.geo;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.geo.GeoJson;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.geo.GeometryTestUtils;
import org.elasticsearch.geometry.Point;
import org.elasticsearch.geometry.utils.WellKnownText;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.TestGeoShapeFieldMapperPlugin;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.index.query.QueryBuilders.geoShapeQuery;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;

public class GeoPointShapeQueryTests extends GeoPointShapeQueryTestCase {

    @SuppressWarnings("deprecation")
    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(TestGeoShapeFieldMapperPlugin.class);
    }

    @Override
    protected void createMapping(String indexName, String fieldName, Settings settings) throws Exception {
        XContentBuilder xcb = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject(fieldName)
            .field("type", "geo_point")
            .endObject()
            .endObject()
            .endObject();
        client().admin().indices().prepareCreate(indexName).setMapping(xcb).setSettings(settings).get();
    }

    public void testFieldAlias() throws IOException {
        String mapping = Strings.toString(
            XContentFactory.jsonBuilder()
                .startObject()
                .startObject("properties")
                .startObject(defaultGeoFieldName)
                .field("type", "geo_point")
                .endObject()
                .startObject("alias")
                .field("type", "alias")
                .field("path", defaultGeoFieldName)
                .endObject()
                .endObject()
                .endObject()
        );

        client().admin().indices().prepareCreate(defaultIndexName).setMapping(mapping).get();
        ensureGreen();

        Point point = GeometryTestUtils.randomPoint(false);
        client().prepareIndex(defaultIndexName)
            .setId("1")
            .setSource(jsonBuilder().startObject().field(defaultGeoFieldName, WellKnownText.toWKT(point)).endObject())
            .setRefreshPolicy(IMMEDIATE)
            .get();

        SearchResponse response = client().prepareSearch(defaultIndexName).setQuery(geoShapeQuery("alias", point)).get();
        assertEquals(1, response.getHits().getTotalHits().value);
    }

    /**
     * Produce an array of objects each representing a single point in a variety of
     * supported point formats. For `geo_shape` we only support GeoJSON and WKT,
     * while for `geo_point` we support a variety of additional special case formats.
     * Therefor we define here sample data for <code>double[]{lon,lat}</code> as well as
     * a string "lat,lon".
     */
    @Override
    protected Object[] samplePointDataMultiFormat(Point pointA, Point pointB, Point pointC, Point pointD) {
        String str = "" + pointA.getLat() + ", " + pointA.getLon();
        String wkt = WellKnownText.toWKT(pointB);
        double[] pointDoubles = new double[] { pointC.getLon(), pointC.getLat() };
        Map<String, Object> geojson = GeoJson.toMap(pointD);
        return new Object[] { str, wkt, pointDoubles, geojson };
    }
}
