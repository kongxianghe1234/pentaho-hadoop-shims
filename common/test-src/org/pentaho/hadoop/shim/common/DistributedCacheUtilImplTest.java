/*******************************************************************************
 *
 * Pentaho Big Data
 *
 * Copyright (C) 2002-2017 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.hadoop.shim.common;

import org.apache.commons.vfs2.AllFileSelector;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSelector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.hadoop.shim.HadoopConfiguration;
import org.pentaho.hadoop.shim.common.fs.PathProxy;
import org.pentaho.hadoop.shim.spi.MockHadoopShim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.mockito.Mockito;
import org.mockito.Matchers;
import org.junit.Assert;

/**
 * Test the DistributedCacheUtil
 */
public class DistributedCacheUtilImplTest {

  private static HadoopConfiguration TEST_CONFIG;
  private static String PLUGIN_BASE = null;

  @BeforeClass
  public static void setup() throws Exception {
    // Create some Hadoop configuration specific pmr libraries
    TEST_CONFIG = new HadoopConfiguration(
      createTestHadoopConfiguration( "bin/test/" + DistributedCacheUtilImplTest.class.getSimpleName() ), "test-config",
      "name", new MockHadoopShim() );

    PLUGIN_BASE = System.getProperty( Const.PLUGIN_BASE_FOLDERS_PROP );
    // Fake out the "plugins" directory for the project's root directory
    System.setProperty( Const.PLUGIN_BASE_FOLDERS_PROP, KettleVFS.getFileObject( "." ).getURL().toURI().getPath() );
  }

  @AfterClass
  public static void teardown() {
    if ( PLUGIN_BASE != null ) {
      System.setProperty( Const.PLUGIN_BASE_FOLDERS_PROP, PLUGIN_BASE );
    }
  }

  private FileSystem getLocalFileSystem( Configuration conf ) throws IOException {
    FileSystem fs = org.apache.hadoop.fs.FileSystem.getLocal( conf );
    try {
      Method setWriteChecksum = fs.getClass().getMethod( "setWriteChecksum", boolean.class );
      setWriteChecksum.invoke( fs, false );
    } catch ( Exception ex ) {
      // ignore, this Hadoop implementation doesn't support checksum verification
    }
    return fs;
  }

  private FileObject createTestFolderWithContent() throws Exception {
    return createTestFolderWithContent( "sample-folder" );
  }

  private FileObject createTestFolderWithContent( String rootFolderName ) throws Exception {
    String rootName = "bin/test/" + rootFolderName;
    FileObject root = KettleVFS.getFileObject( rootName );
    root.resolveFile( "jar1.jar" ).createFile();
    root.resolveFile( "jar2.jar" ).createFile();
    root.resolveFile( "folder" ).resolveFile( "file.txt" ).createFile();
    root.resolveFile( "pentaho-mapreduce-libraries.zip" ).createFile();

    createTestHadoopConfiguration( rootName );

    return root;
  }

  private static FileObject createTestHadoopConfiguration( String rootFolderName ) throws Exception {
    FileObject location = KettleVFS.getFileObject( rootFolderName + "/hadoop-configurations/test-config" );

    FileObject lib = location.resolveFile( "lib" );
    FileObject libPmr = lib.resolveFile( "pmr" );
    FileObject pmrLibJar = libPmr.resolveFile( "configuration-specific.jar" );

    lib.createFolder();
    lib.resolveFile( "required.jar" ).createFile();

    libPmr.createFolder();
    pmrLibJar.createFile();

    return location;
  }

  @Test( expected = NullPointerException.class )
  public void instantiation() {
    new DistributedCacheUtilImpl( null );
  }

  @Test
  public void deleteDirectory() throws Exception {
    FileObject test = KettleVFS.getFileObject( "bin/test/deleteDirectoryTest" );
    test.createFolder();

    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );
    ch.deleteDirectory( test );
    try {
      Assert.assertFalse( test.exists() );
    } finally {
      // Delete the directory with java.io.File if it wasn't removed
      File f = new File( "bin/test/deleteDirectoryTest" );
      if ( f.exists() && !f.delete() ) {
        throw new IOException( "unable to delete test directory: " + f.getAbsolutePath() );
      }
    }
  }

  @Test
  public void extract_invalid_archive() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    try {
      ch.extract( KettleVFS.getFileObject( "bogus" ), null );
      Assert.fail( "expected exception" );
    } catch ( IllegalArgumentException ex ) {
      Assert.assertTrue( ex.getMessage().startsWith( "archive does not exist" ) );
    }
  }

  @Test
  public void extract_destination_exists() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    FileObject archive =
      KettleVFS.getFileObject( getClass().getResource( "/pentaho-mapreduce-sample.jar" ).toURI().getPath() );

    try {
      ch.extract( archive, KettleVFS.getFileObject( "." ) );
    } catch ( IllegalArgumentException ex ) {
      Assert.assertTrue( ex.getMessage(), "destination already exists".equals( ex.getMessage() ) );
    }
  }

  @Test
  public void extractToTemp() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    FileObject archive =
      KettleVFS.getFileObject( getClass().getResource( "/pentaho-mapreduce-sample.jar" ).toURI().getPath() );
    FileObject extracted = ch.extractToTemp( archive );

    Assert.assertNotNull( extracted );
    Assert.assertTrue( extracted.exists() );
    try {
      // There should be 3 files and 5 directories inside the root folder (which is the 9th entry)
      Assert.assertTrue( extracted.findFiles( new AllFileSelector() ).length == 9 );
    } finally {
      // clean up after ourself
      ch.deleteDirectory( extracted );
    }
  }

  @Test
  public void extractToTempZipEntriesMixed() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    File dest = File.createTempFile( "entriesMixed", ".zip" );
    ZipOutputStream outputStream = new ZipOutputStream( new FileOutputStream( dest ) );
    ZipEntry e = new ZipEntry( "zipEntriesMixed" + "/" + "someFile.txt" );
    outputStream.putNextEntry( e );
    byte[] data = "someOutString".getBytes();
    outputStream.write( data, 0, data.length );
    outputStream.closeEntry();
    e = new ZipEntry(  "zipEntriesMixed" + "/" );
    outputStream.putNextEntry( e );
    outputStream.closeEntry();
    outputStream.close();

    FileObject archive = KettleVFS.getFileObject( dest.getAbsolutePath() );

    FileObject extracted = null;
    try {
      extracted = ch.extractToTemp( archive );
    } catch ( IOException | KettleFileException e1 ) {
      e1.printStackTrace();
      Assert.fail( "Exception not expected in this case" );
    }

    Assert.assertNotNull( extracted );
    Assert.assertTrue( extracted.exists() );
    try {
      // There should be 3 files and 5 directories inside the root folder (which is the 9th entry)
      Assert.assertTrue( extracted.findFiles( new AllFileSelector() ).length == 3 );
    } finally {
      // clean up after ourself
      ch.deleteDirectory( extracted );
      dest.delete();
    }
  }

  @Test
  public void extractToTemp_missing_archive() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    try {
      ch.extractToTemp( null );
      Assert.fail( "Expected exception" );
    } catch ( NullPointerException ex ) {
      Assert.assertEquals( "archive is required", ex.getMessage() );
    }
  }

  @Test
  public void findFiles_vfs() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    FileObject testFolder = createTestFolderWithContent();

    try {
      // Simply test we can find the jar files in our test folder
      List<String> jars = ch.findFiles( testFolder, "jar" );
      Assert.assertEquals( 4, jars.size() );

      // Look for all files and folders
      List<String> all = ch.findFiles( testFolder, null );
      Assert.assertEquals( 12, all.size() );
    } finally {
      testFolder.delete( new AllFileSelector() );
    }
  }

  @Test
  public void findFiles_vfs_hdfs() throws Exception {

    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    URL url = new URL( "http://localhost:8020/path/to/file" );
    Configuration conf = Mockito.mock( Configuration.class );
    FileSystem fs = Mockito.mock( FileSystem.class );
    FileObject source = Mockito.mock( FileObject.class );
    Path dest = Mockito.mock( Path.class );
    FileObject hdfsDest = Mockito.mock( FileObject.class );
    Path root = Mockito.mock( Path.class );

    FileObject[] fileObjects = new FileObject[12];
    for ( int i = 0; i < fileObjects.length; i++ ) {
      URL fileUrl = new URL( "http://localhost:8020/path/to/file/" + i );
      FileObject fileObject = Mockito.mock( FileObject.class );
      fileObjects[i] = fileObject;
      Mockito.doReturn( fileUrl ).when( fileObject ).getURL();
    }

    Mockito.doReturn( url ).when( source ).getURL();
    Mockito.doReturn( conf ).when( fs ).getConf();
    Mockito.doReturn( 0 ).when( conf ).getInt( Matchers.any( String.class ), Mockito.anyInt() );
    Mockito.doReturn( true ).when( source ).exists();
    Mockito.doReturn( fileObjects ).when( hdfsDest ).findFiles( Matchers.any( FileSelector.class ) );
    Mockito.doReturn( true ).when( fs ).delete( root, true );
    Mockito.doReturn( fileObjects.length ).when( source ).delete( Matchers.any( AllFileSelector.class ) );
    Mockito.doNothing().when( fs ).copyFromLocalFile( Matchers.any( Path.class ), Matchers.any( Path.class ) );
    Mockito.doNothing().when( fs ).setPermission( Matchers.any( Path.class ), Matchers.any( FsPermission.class ) );
    Mockito.doReturn( true ).when( fs ).setReplication( Matchers.any( Path.class ), Mockito.anyShort() );

    try {
      try {
        ch.stageForCache( source, fs, dest, true );

        List<String> files = ch.findFiles( hdfsDest, null );
        Assert.assertEquals( 12, files.size() );
      } finally {
        fs.delete( root, true );
      }
    } finally {
      source.delete( new AllFileSelector() );
    }
  }

  @Test
  public void findFiles_hdfs_native() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    // Copy the contents of test folder
    FileObject source = createTestFolderWithContent();
    Path root = new Path( "bin/test/stageArchiveForCacheTest" );
    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );
    Path dest = new Path( root, "org/pentaho/mapreduce/" );
    try {
      try {
        ch.stageForCache( source, fs, dest, true );

        List<Path> files = ch.findFiles( fs, dest, null );
        Assert.assertEquals( 5, files.size() );

        files = ch.findFiles( fs, dest, Pattern.compile( ".*jar$" ) );
        Assert.assertEquals( 2, files.size() );

        files = ch.findFiles( fs, dest, Pattern.compile( ".*folder$" ) );
        Assert.assertEquals( 1, files.size() );
      } finally {
        fs.delete( root, true );
      }
    } finally {
      source.delete( new AllFileSelector() );
    }
  }

  /**
   * Utility to attempt to stage a file to HDFS for use with Distributed Cache.
   *
   * @param ch                Distributed Cache Helper
   * @param source            File or directory to stage
   * @param fs                FileSystem to stage to
   * @param root              Root directory to clean up when this test is complete
   * @param dest              Destination path to stage to
   * @param expectedFileCount Expected number of files to exist in the destination once staged
   * @param expectedDirCount  Expected number of directories to exist in the destiation once staged
   * @throws Exception
   */
  private void stageForCacheTester( DistributedCacheUtilImpl ch, FileObject source, FileSystem fs, Path root, Path dest,
                                    int expectedFileCount, int expectedDirCount ) throws Exception {
    try {
      ch.stageForCache( source, fs, dest, true );

      Assert.assertTrue( fs.exists( dest ) );
      ContentSummary cs = fs.getContentSummary( dest );
      Assert.assertEquals( expectedFileCount, cs.getFileCount() );
      Assert.assertEquals( expectedDirCount, cs.getDirectoryCount() );
      Assert.assertEquals( FsPermission.createImmutable( (short) 0755 ), fs.getFileStatus( dest ).getPermission() );
    } finally {
      // Clean up after ourself
      if ( !fs.delete( root, true ) ) {
        System.err.println( "error deleting FileSystem temp dir " + root );
      }
    }
  }

  @Test
  public void stageForCache() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    // Copy the contents of test folder
    FileObject source = createTestFolderWithContent();

    try {
      Path root = new Path( "bin/test/stageArchiveForCacheTest" );
      Path dest = new Path( root, "org/pentaho/mapreduce/" );

      Configuration conf = new Configuration();
      FileSystem fs = getLocalFileSystem( conf );

      stageForCacheTester( ch, source, fs, root, dest, 6, 6 );
    } finally {
      source.delete( new AllFileSelector() );
    }
  }

  @Test
  public void stageForCache_missing_source() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    Path dest = new Path( "bin/test/bogus-destination" );
    FileObject bogusSource = KettleVFS.getFileObject( "bogus" );
    try {
      ch.stageForCache( bogusSource, fs, dest, true );
      Assert.fail( "expected exception when source does not exist" );
    } catch ( KettleFileException ex ) {
      Assert.assertEquals( BaseMessages
          .getString( DistributedCacheUtilImpl.class, "DistributedCacheUtil.SourceDoesNotExist", bogusSource ),
        ex.getMessage().trim() );
    }
  }

  @Test
  public void stageForCache_destination_no_overwrite() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    FileObject source = createTestFolderWithContent();
    try {
      Path root = new Path( "bin/test/stageForCache_destination_exists" );
      Path dest = new Path( root, "dest" );

      fs.mkdirs( dest );
      Assert.assertTrue( fs.exists( dest ) );
      Assert.assertTrue( fs.getFileStatus( dest ).isDir() );
      try {
        ch.stageForCache( source, fs, dest, false );
      } catch ( KettleFileException ex ) {
        Assert.assertTrue( ex.getMessage(), ex.getMessage().contains( "Destination exists" ) );
      } finally {
        fs.delete( root, true );
      }
    } finally {
      source.delete( new AllFileSelector() );
    }
  }

  @Test
  public void stageForCache_destination_exists() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    FileObject source = createTestFolderWithContent();
    try {
      Path root = new Path( "bin/test/stageForCache_destination_exists" );
      Path dest = new Path( root, "dest" );

      fs.mkdirs( dest );
      Assert.assertTrue( fs.exists( dest ) );
      Assert.assertTrue( fs.getFileStatus( dest ).isDir() );

      stageForCacheTester( ch, source, fs, root, dest, 6, 6 );
    } finally {
      source.delete( new AllFileSelector() );
    }
  }

  @Test
  public void addCachedFilesToClasspath() throws IOException {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );
    Configuration conf = new Configuration();

    List<Path> files = Arrays.asList( new Path( "a" ), new Path( "b" ), new Path( "c" ) );

    ch.addCachedFilesToClasspath( files, conf );

    // this check is not needed for each and every shim
    if ( "true".equals( System.getProperty( "org.pentaho.hadoop.shims.check.symlink", "false" ) ) ) {
      Assert.assertEquals( "yes", conf.get( "mapred.create.symlink" ) );
    }

    for ( Path file : files ) {
      Assert.assertTrue( conf.get( "mapred.cache.files" ).contains( file.toString() ) );
      Assert.assertTrue( conf.get( "mapred.job.classpath.files" ).contains( file.toString() ) );
    }
  }

  @Test
  public void isPmrInstalledAt() throws IOException {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    Path root = new Path( "bin/test/ispmrInstalledAt" );
    Path lib = new Path( root, "lib" );
    Path plugins = new Path( root, "plugins" );
    Path bigDataPlugin = new Path( plugins, DistributedCacheUtilImpl.PENTAHO_BIG_DATA_PLUGIN_FOLDER_NAME );

    Path lockFile = ch.getLockFileAt( root );
    try {
      // Create all directories (parent directories created automatically)
      fs.mkdirs( lib );
      fs.mkdirs( bigDataPlugin );

      Assert.assertTrue( ch.isKettleEnvironmentInstalledAt( fs, root ) );

      // If lock file is there pmr is not installed
      fs.create( lockFile );
      Assert.assertFalse( ch.isKettleEnvironmentInstalledAt( fs, root ) );

      // Try to create a file instead of a directory for the pentaho-big-data-plugin. This should be detected.
      fs.delete( bigDataPlugin, true );
      fs.create( bigDataPlugin );
      Assert.assertFalse( ch.isKettleEnvironmentInstalledAt( fs, root ) );
    } finally {
      fs.delete( root, true );
    }
  }

  @Test
  public void installKettleEnvironment_missing_arguments() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    try {
      ch.installKettleEnvironment( null, (org.pentaho.hadoop.shim.api.fs.FileSystem) null, null, null, null );
      Assert.fail( "Expected exception on missing archive" );
    } catch ( NullPointerException ex ) {
      Assert.assertEquals( "pmrArchive is required", ex.getMessage() );
    }

    try {
      ch.installKettleEnvironment( KettleVFS.getFileObject( "." ), (org.pentaho.hadoop.shim.api.fs.FileSystem) null,
        null, null, null );
      Assert.fail( "Expected exception on missing archive" );
    } catch ( NullPointerException ex ) {
      Assert.assertEquals( "destination is required", ex.getMessage() );
    }

    try {
      ch.installKettleEnvironment( KettleVFS.getFileObject( "." ), (org.pentaho.hadoop.shim.api.fs.FileSystem) null,
        new PathProxy( "." ), null, null );
      Assert.fail( "Expected exception on missing archive" );
    } catch ( NullPointerException ex ) {
      Assert.assertEquals( "big data plugin required", ex.getMessage() );
    }
  }

  @Test
  public void installKettleEnvironment() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    // This "empty pmr" contains a lib/ folder but with no content
    FileObject pmrArchive = KettleVFS.getFileObject( getClass().getResource( "/empty-pmr.zip" ).toURI().getPath() );

    FileObject bigDataPluginDir =
      createTestFolderWithContent( DistributedCacheUtilImpl.PENTAHO_BIG_DATA_PLUGIN_FOLDER_NAME );

    Path root = new Path( "bin/test/installKettleEnvironment" );
    try {
      ch.installKettleEnvironment( pmrArchive, fs, root, bigDataPluginDir, null );
      Assert.assertTrue( ch.isKettleEnvironmentInstalledAt( fs, root ) );
    } finally {
      bigDataPluginDir.delete( new AllFileSelector() );
      fs.delete( root, true );
    }
  }

  @Test
  public void installKettleEnvironment_additional_plugins() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    // This "empty pmr" contains a lib/ folder but with no content
    FileObject pmrArchive = KettleVFS.getFileObject( getClass().getResource( "/empty-pmr.zip" ).toURI().getPath() );

    FileObject bigDataPluginDir =
      createTestFolderWithContent( DistributedCacheUtilImpl.PENTAHO_BIG_DATA_PLUGIN_FOLDER_NAME );
    String pluginName = "additional-plugin";
    createTestFolderWithContent( pluginName );
    Path root = new Path( "bin/test/installKettleEnvironment" );
    try {
      ch.installKettleEnvironment( pmrArchive, fs, root, bigDataPluginDir, "bin/test/" + pluginName );
      Assert.assertTrue( ch.isKettleEnvironmentInstalledAt( fs, root ) );
      Assert.assertTrue( fs.exists( new Path( root, "plugins/bin/test/" + pluginName ) ) );
    } finally {
      bigDataPluginDir.delete( new AllFileSelector() );
      fs.delete( root, true );
    }
  }

  @Test
  public void stagePluginsForCache() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    Path pluginsDir = new Path( "bin/test/plugins-installation-dir" );

    FileObject pluginDir = createTestFolderWithContent();

    try {
      ch.stagePluginsForCache( fs, pluginsDir, "bin/test/sample-folder" );
      Path pluginInstallPath = new Path( pluginsDir, "bin/test/sample-folder" );
      Assert.assertTrue( fs.exists( pluginInstallPath ) );
      ContentSummary summary = fs.getContentSummary( pluginInstallPath );
      Assert.assertEquals( 6, summary.getFileCount() );
      Assert.assertEquals( 6, summary.getDirectoryCount() );
    } finally {
      pluginDir.delete( new AllFileSelector() );
      fs.delete( pluginsDir, true );
    }
  }

  @Test( expected = IllegalArgumentException.class )
  public void stagePluginsForCache_no_folders() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );
    ch.stagePluginsForCache( getLocalFileSystem( new Configuration() ), new Path( "bin/test/plugins-installation-dir" ),
      null );
  }

  @Test( expected = KettleFileException.class )
  public void stagePluginsForCache_invalid_folder() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );
    ch.stagePluginsForCache( getLocalFileSystem( new Configuration() ), new Path( "bin/test/plugins-installation-dir" ),
      "bin/bogus-plugin-name" );
  }

  @Test
  public void configureWithPmr() throws Exception {
    DistributedCacheUtilImpl ch = new DistributedCacheUtilImpl( TEST_CONFIG );

    Configuration conf = new Configuration();
    FileSystem fs = getLocalFileSystem( conf );

    // This "empty pmr" contains a lib/ folder and some empty kettle-*.jar files but no actual content
    FileObject pmrArchive = KettleVFS.getFileObject( getClass().getResource( "/empty-pmr.zip" ).toURI().getPath() );

    FileObject bigDataPluginDir =
      createTestFolderWithContent( DistributedCacheUtilImpl.PENTAHO_BIG_DATA_PLUGIN_FOLDER_NAME );

    Path root = new Path( "bin/test/installKettleEnvironment" );
    try {
      ch.installKettleEnvironment( pmrArchive, fs, root, bigDataPluginDir, null );
      Assert.assertTrue( ch.isKettleEnvironmentInstalledAt( fs, root ) );

      ch.configureWithKettleEnvironment( conf, fs, root );

      // Make sure our libraries are on the classpath
      Assert.assertTrue( conf.get( "mapred.cache.files" ).contains( "lib/kettle-core.jar" ) );
      Assert.assertTrue( conf.get( "mapred.cache.files" ).contains( "lib/kettle-engine.jar" ) );
      Assert.assertTrue( conf.get( "mapred.job.classpath.files" ).contains( "lib/kettle-core.jar" ) );
      Assert.assertTrue( conf.get( "mapred.job.classpath.files" ).contains( "lib/kettle-engine.jar" ) );

      // Make sure the configuration specific jar made it!
      Assert.assertTrue( conf.get( "mapred.cache.files" ).contains( "lib/configuration-specific.jar" ) );

      // Make sure our plugins folder is registered
      Assert.assertTrue( conf.get( "mapred.cache.files" ).contains( "#plugins" ) );

      // Make sure our libraries aren't included twice
      Assert.assertFalse( conf.get( "mapred.cache.files" ).contains( "#lib" ) );

      // We should not have individual files registered
      Assert.assertFalse( conf.get( "mapred.cache.files" ).contains( "pentaho-big-data-plugin/jar1.jar" ) );
      Assert.assertFalse( conf.get( "mapred.cache.files" ).contains( "pentaho-big-data-plugin/jar2.jar" ) );
      Assert.assertFalse( conf.get( "mapred.cache.files" ).contains( "pentaho-big-data-plugin/folder/file.txt" ) );

    } finally {
      bigDataPluginDir.delete( new AllFileSelector() );
      fs.delete( root, true );
    }
  }

  @Test
  public void findPluginFolder() throws Exception {
    DistributedCacheUtilImpl util = new DistributedCacheUtilImpl( TEST_CONFIG );

    // Fake out the "plugins" directory for the project's root directory
    String originalValue = System.getProperty( Const.PLUGIN_BASE_FOLDERS_PROP );
    System.setProperty( Const.PLUGIN_BASE_FOLDERS_PROP, KettleVFS.getFileObject( "." ).getURL().toURI().getPath() );

    Assert.assertNotNull( "Should have found plugin dir: bin/", util.findPluginFolder( "bin" ) );
    Assert.assertNotNull( "Should be able to find nested plugin dir: bin/test/", util.findPluginFolder( "bin/test" ) );

    Assert.assertNull( "Should not have found plugin dir: org/", util.findPluginFolder( "org" ) );
    System.setProperty( Const.PLUGIN_BASE_FOLDERS_PROP, originalValue );
  }

  @Test
  public void addFilesToClassPath() throws IOException {
    DistributedCacheUtilImpl util = new DistributedCacheUtilImpl( TEST_CONFIG );
    Path p1 = new Path( "/testing1" );
    Path p2 = new Path( "/testing2" );
    Configuration conf = new Configuration();
    util.addFileToClassPath( p1, conf );
    util.addFileToClassPath( p2, conf );
    Assert.assertEquals( "/testing1:/testing2", conf.get( "mapred.job.classpath.files" ) );
  }

  @Test
  public void addFilesToClassPath_custom_path_separator() throws IOException {
    DistributedCacheUtilImpl util = new DistributedCacheUtilImpl( TEST_CONFIG );
    Path p1 = new Path( "/testing1" );
    Path p2 = new Path( "/testing2" );
    Configuration conf = new Configuration();
    String originalValue = System.getProperty( "hadoop.cluster.path.separator", ":" );
    System.setProperty( "hadoop.cluster.path.separator", "J" );

    util.addFileToClassPath( p1, conf );
    util.addFileToClassPath( p2, conf );
    Assert.assertEquals( "/testing1J/testing2", conf.get( "mapred.job.classpath.files" ) );
    System.setProperty( "hadoop.cluster.path.separator", originalValue );
  }
}
