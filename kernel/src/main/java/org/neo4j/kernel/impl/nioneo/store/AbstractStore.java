/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.neo4j.helpers.UTF8;
import org.neo4j.kernel.IdGeneratorFactory;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.impl.core.ReadOnlyDbException;
import org.neo4j.kernel.impl.util.StringLogger;

/**
 * An abstract representation of a store. A store is a file that contains
 * records. Each record has a fixed size (<CODE>getRecordSize()</CODE>) so
 * the position for a record can be calculated by 
 * <CODE>id * getRecordSize()</CODE>.
 * <p>
 * A store has an {@link IdGenerator} managing the records that are free or in
 * use.
 */
public abstract class AbstractStore extends CommonAbstractStore
{
    /**
     * Returnes the fixed size of each record in this store.
     * 
     * @return The record size
     */
    public abstract int getRecordSize();
    
    @Override
    protected long figureOutHighestIdInUse()
    {
        try
        {
            return getFileChannel().size()/getRecordSize();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    /**
     * Creates a new empty store. The factory method returning an implementation
     * of some store type should make use of this method to initialize an empty
     * store.
     * <p>
     * This method will create a empty store containing the descriptor returned
     * by the <CODE>getTypeAndVersionDescriptor()</CODE>. The id generator
     * used by this store will also be created
     * 
     * @param fileName
     *            The file name of the store that will be created
     * @param typeAndVersionDescriptor
     *            The type and version descriptor that identifies this store
     * @throws IOException
     *             If fileName is null or if file exists
     */
    protected static void createEmptyStore( String fileName,
        String typeAndVersionDescriptor, IdGeneratorFactory idGeneratorFactory )
    {
        // sanity checks
        if ( fileName == null )
        {
            throw new IllegalArgumentException( "Null filename" );
        }
        File file = new File( fileName );
        if ( file.exists() )
        {
            throw new IllegalStateException( "Can't create store[" + fileName
                + "], file already exists" );
        }

        // write the header
        try
        {
            FileChannel channel = new FileOutputStream( fileName ).getChannel();
            int endHeaderSize = UTF8.encode( typeAndVersionDescriptor ).length;
            ByteBuffer buffer = ByteBuffer.allocate( endHeaderSize );
            buffer.put( UTF8.encode( typeAndVersionDescriptor ) ).flip();
            channel.write( buffer );
            channel.force( false );
            channel.close();
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( "Unable to create store "
                + fileName, e );
        }
        idGeneratorFactory.create( fileName + ".id" );
    }

    public AbstractStore( String fileName, Map<?,?> config, IdType idType )
    {
        super( fileName, config, idType );
    }

//    public AbstractStore( String fileName )
//    {
//        super( fileName );
//    }

    protected void loadStorage()
    {
        try
        {
            long fileSize = getFileChannel().size();
            String expectedVersion = getTypeAndVersionDescriptor();
            byte version[] = new byte[UTF8.encode( expectedVersion ).length];
            ByteBuffer buffer = ByteBuffer.wrap( version );
            if ( fileSize >= version.length )
            {
                getFileChannel().position( fileSize - version.length );
            }
            else if ( !isReadOnly() )
            {
                setStoreNotOk();
            }
            getFileChannel().read( buffer );
            if ( !expectedVersion.equals( UTF8.decode( version ) ) && !isReadOnly() )
            {
                if ( !versionFound( UTF8.decode( version ) ) )
                {
                    setStoreNotOk();
                }
            }
            if ( getRecordSize() != 0
                && (fileSize - version.length) % getRecordSize() != 0  && !isReadOnly() )
            {
                setStoreNotOk();
            }
            if ( getStoreOk() && !isReadOnly() )
            {
                getFileChannel().truncate( fileSize - version.length );
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( "Unable to load store "
                + getStorageFileName(), e );
        }
        try
        {
            if ( !isReadOnly() || isBackupSlave() )
            {
                openIdGenerator();
            }
            else
            {
                openReadOnlyIdGenerator( getRecordSize() );
            }
        }
        catch ( InvalidIdGeneratorException e )
        {
            setStoreNotOk();
        }
        finally 
        {
            if ( !getStoreOk() )
            {
                if ( getConfig() != null )
                {
                    String storeDir = (String) getConfig().get( "store_dir" );
                    StringLogger msgLog = StringLogger.getLogger( storeDir );
                    msgLog.logMessage( getStorageFileName() + " non clean shutdown detected", true );
                }
            }
        }
        setWindowPool( new PersistenceWindowPool( getStorageFileName(),
            getRecordSize(), getFileChannel(), calculateMappedMemory( getConfig(), storageFileName ),
            getIfMemoryMapped(), isReadOnly() && !isBackupSlave() ) );
    }

    /**
     * Sets the high id of {@link IdGenerator}.
     * 
     * @param id
     *            The high id
     */
    public void setHighId( int id )
    {
        super.setHighId( id );
    }
    
//    @Override
//    protected void updateHighId()
//    {
//        try
//        {
//            long highId = getFileChannel().size() / getRecordSize();
//            
//            if ( highId > getHighId() )
//            {
//                setHighId( highId );
//            }
//        }
//        catch ( IOException e )
//        {
//            throw new UnderlyingStorageException( e );
//        }
//    }

    private long findHighIdBackwards() throws IOException
    {
        // Duplicated method
        FileChannel fileChannel = getFileChannel();
        int recordSize = getRecordSize();
        long fileSize = fileChannel.size();
        long highId = fileSize / recordSize;
        ByteBuffer byteBuffer = ByteBuffer.allocate( 1 );
        for ( long i = highId; i > 0; i-- )
        {
            fileChannel.position( i * recordSize );
            if ( fileChannel.read( byteBuffer ) > 0 )
            {
                byteBuffer.flip();
                byte inUse = byteBuffer.get();
                byteBuffer.clear();
                if ( inUse != 0 )
                {
                    return i;
                }
            }
        }
        return 0;
    }
    
    /**
     * Rebuilds the {@link IdGenerator} by looping through all records and
     * checking if record in use or not.
     * 
     * @throws IOException
     *             if unable to rebuild the id generator
     */
    protected void rebuildIdGenerator()
    {
        if ( isReadOnly() && !isBackupSlave() )
        {
            throw new ReadOnlyDbException();
        }

        logger.fine( "Rebuilding id generator for[" + getStorageFileName()
            + "] ..." );
        closeIdGenerator();
        File file = new File( getStorageFileName() + ".id" );
        if ( file.exists() )
        {
            boolean success = file.delete();
            assert success;
        }
        createIdGenerator( getStorageFileName() + ".id" );
        openIdGenerator();
        FileChannel fileChannel = getFileChannel();
        long highId = 1;
        long defraggedCount = 0;
        try
        {
            long fileSize = fileChannel.size();
            int recordSize = getRecordSize();
            boolean fullRebuild = true;
            if ( getConfig() != null )
            {
                String mode = (String) 
                    getConfig().get( "rebuild_idgenerators_fast" );
                if ( mode != null && mode.toLowerCase().equals( "true" ) )
                {
                    fullRebuild = false;
                    highId = findHighIdBackwards();
                }
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap( new byte[1] );
            // Duplicated code block
            LinkedList<Long> freeIdList = new LinkedList<Long>();
            if ( fullRebuild )
            {
                for ( long i = 0; i * recordSize < fileSize && recordSize > 0; 
                    i++ )
                {
                    fileChannel.position( i * recordSize );
                    fileChannel.read( byteBuffer );
                    byteBuffer.flip();
                    byte inUse = byteBuffer.get();
                    byteBuffer.flip();
                    nextId();
                    if ( (inUse & 0x1) == Record.NOT_IN_USE.byteValue() )
                    {
                        freeIdList.add( i );
                    }
                    else
                    {
                        highId = i;
                        while ( !freeIdList.isEmpty() )
                        {
                            freeId( freeIdList.removeFirst() );
                            defraggedCount++;
                        }
                    }
                }
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( 
                "Unable to rebuild id generator " + getStorageFileName(), e );
        }
        setHighId( highId + 1 );
        if ( getConfig() != null )
        {
            String storeDir = (String) getConfig().get( "store_dir" );
            StringLogger msgLog = StringLogger.getLogger( storeDir );
            msgLog.logMessage( getStorageFileName() + " rebuild id generator, highId=" + getHighId() + 
                    " defragged count=" + defraggedCount, true );
        }
        logger.fine( "[" + getStorageFileName() + "] high id=" + getHighId()
            + " (defragged=" + defraggedCount + ")" );
        closeIdGenerator();
        openIdGenerator();
    }
    
    public abstract List<WindowPoolStats> getAllWindowPoolStats();
}