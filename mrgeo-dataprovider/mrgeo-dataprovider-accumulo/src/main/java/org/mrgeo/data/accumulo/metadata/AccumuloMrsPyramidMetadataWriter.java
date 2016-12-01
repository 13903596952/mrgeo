/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 */

package org.mrgeo.data.accumulo.metadata;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.mrgeo.data.DataProviderException;
import org.mrgeo.data.accumulo.utils.AccumuloConnector;
import org.mrgeo.data.accumulo.utils.MrGeoAccumuloConstants;
import org.mrgeo.data.image.MrsImageDataProvider;
import org.mrgeo.data.image.MrsPyramidMetadataWriter;
import org.mrgeo.data.image.MrsPyramidMetadataWriterContext;
import org.mrgeo.image.MrsPyramidMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;

public class AccumuloMrsPyramidMetadataWriter implements MrsPyramidMetadataWriter
{

private static final Logger log = LoggerFactory.getLogger(AccumuloMrsPyramidMetadataWriter.class);

private final MrsImageDataProvider provider;

private Connector conn = null;

/**
 * Constructor for HdfsMrsPyramidMetadataWriter.
 * @param provider MrsImageDataProvider
 * @param context MrsPyramidMetadataWriterContext
 */
public AccumuloMrsPyramidMetadataWriter(MrsImageDataProvider provider,
    MrsPyramidMetadataWriterContext context)
{
  this.provider = provider;

} // end constructor


/**
 * Write the (already loaded) metadata for the provider to Accumulo
 * @throws IOException
 * @see MrsPyramidMetadataWriter#write()
 */
@Override
public void write() throws IOException
{
  MrsPyramidMetadata metadata = provider.getMetadataReader(null).read();

  write(metadata);
} // end write

/**
 *
 * need to know the type of table/image this is
 * if it is a standalone - then there is one table
 * if not - we write to a table that has a bunch of data in it
 *
 *
 *
 *
 *
 */
@Override
@SuppressWarnings("squid:S1166") // Exception caught and handled
public void write(MrsPyramidMetadata metadata) throws IOException{
  Properties mrgeoAccProps = AccumuloConnector.getAccumuloProperties();

  String pl = metadata.getProtectionLevel();

  if(conn == null){
    conn = AccumuloConnector.getConnector();
  }
    
    /*
     *  need to know the type of table/image this is
     *  
     *  if it is a standalone - then there is one table
     *  
     *  if not - we write to a table that has a bunch of data in it
     *  
     */

  String table = provider.getResourceName();
  if(table == null || table.length() == 0){
    throw new IOException("Can not load metadata, resource name is empty!");
  }

  try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
  {
    String metadataStr = null;
    metadata.save(baos);
    metadataStr = baos.toString();
    baos.close();


    /**
     * TODO: when the protection levels are in the metadata
     * class then pull them out and put them in the visualization
     * fields.
     */
    ColumnVisibility cv;
    if (pl == null)
    {

      if (mrgeoAccProps.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_VIZ) == null)
      {
        cv = new ColumnVisibility();
      }
      else
      {
        cv = new ColumnVisibility(mrgeoAccProps.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_VIZ));
      }
    }
    else
    {
      cv = new ColumnVisibility(pl);
    }

    log.debug("Writing metadata for table " + metadata.getPyramid() + " with ColumnVisibility = " + cv.toString());

    // this is the name of the image
//    String pyramid = metadata.getPyramid();
    Mutation m = new Mutation(MrGeoAccumuloConstants.MRGEO_ACC_METADATA);
    m.put(MrGeoAccumuloConstants.MRGEO_ACC_METADATA, MrGeoAccumuloConstants.MRGEO_ACC_CQALL, cv,
        new Value(metadataStr.getBytes()));

    BatchWriter bw = null;
      bw = conn.createBatchWriter(table, 10000000L, 1000, 2);
      bw.addMutation(m);
      bw.flush();
      bw.close();
  }
  catch (TableNotFoundException | MutationsRejectedException e)
  {
  }

  provider.getMetadataReader(null).reload();

} // end write


public void setConnector(Connector conn){
  this.conn = conn;
} // end setConnector


} // end AccumuloMrsPyramidMetadataWriter
