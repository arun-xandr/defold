package com.dynamo.cr.client;


import com.dynamo.cr.protocol.proto.Protocol.BranchStatus;
import com.dynamo.cr.protocol.proto.Protocol.BuildDesc;
import com.dynamo.cr.protocol.proto.Protocol.BuildLog;
import com.dynamo.cr.protocol.proto.Protocol.CommitDesc;
import com.dynamo.cr.protocol.proto.Protocol.Log;
import com.dynamo.cr.protocol.proto.Protocol.ResourceInfo;

public interface IBranchClient extends IClient {

    public byte[] getResourceData(String path, String revision) throws RepositoryException;

    public ResourceInfo getResourceInfo(String path)
            throws RepositoryException;

    public BranchStatus getBranchStatus() throws RepositoryException;

    public void putResourceData(String path, byte[] bytes) throws RepositoryException;

    public void mkdir(String path) throws RepositoryException;

    public void deleteResource(String path) throws RepositoryException;

    public void renameResource(String source, String destination) throws RepositoryException;

    public void revertResource(String path)  throws RepositoryException;

    public BranchStatus update() throws RepositoryException;

    public CommitDesc commit(String message) throws RepositoryException;

    public CommitDesc commitMerge(String message) throws RepositoryException;

    public void resolve(String path, String stage) throws RepositoryException;

    public void publish() throws RepositoryException;

    public Log log(int maxCount) throws RepositoryException;

    public void reset(String mode, String target) throws RepositoryException;

    // Build related methods
    public BuildDesc build(boolean rebuild) throws RepositoryException;

    public BuildDesc getBuildStatus(int id) throws RepositoryException;

    public void cancelBuild(int id) throws RepositoryException;

    public BuildLog getBuildLogs(int id) throws RepositoryException;
}
