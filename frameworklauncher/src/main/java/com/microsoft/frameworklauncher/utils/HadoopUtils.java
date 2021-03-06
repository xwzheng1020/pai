// Copyright (c) Microsoft Corporation
// All rights reserved. 
//
// MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated 
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation 
// the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and 
// to permit persons to whom the Software is furnished to do so, subject to the following conditions:
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING 
// BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE. 

package com.microsoft.frameworklauncher.utils;

import com.microsoft.frameworklauncher.common.exceptions.NonTransientException;
import com.microsoft.frameworklauncher.common.model.UserDescriptor;
import com.microsoft.frameworklauncher.common.model.ResourceDescriptor;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.ApplicationNotFoundException;
import org.apache.hadoop.yarn.util.ConverterUtils;

import java.io.FileNotFoundException;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedExceptionAction;
import java.util.*;

public class HadoopUtils {
  private static final DefaultLogger LOGGER = new DefaultLogger(HadoopUtils.class);

  private static final String NODE_LABEL_WILDCARD = "*";
  private static final String NODE_LABEL_DELIMITER = "&";
  private static final String HDFS_PATH_SEPARATOR = "/";

  private static Configuration conf = new YarnConfiguration();

  // Cache for HDFS ResourceAbsolutePath -> ResourceFileStatus
  private static final Map<String, FileStatus> resourceFileStatusCache = new HashMap<>();
  // Cache for Conf ResourceType -> ResourceMinAllocation
  private static final Map<ResourceType, Integer> resourceMinAllocationCache = new HashMap<>();

  // Node can be file or directory
  public static String getHdfsNodePath(String parentNodePath, String nodeName) {
    return (StringUtils.stripEnd(parentNodePath, HDFS_PATH_SEPARATOR) +
        HDFS_PATH_SEPARATOR +
        StringUtils.stripStart(nodeName, HDFS_PATH_SEPARATOR));
  }

  public static String getHdfsNodeName(String hdfsNodePath) {
    Integer t = hdfsNodePath.length() - 1;
    if (hdfsNodePath.endsWith(HDFS_PATH_SEPARATOR)) t--;

    int s = hdfsNodePath.lastIndexOf(HDFS_PATH_SEPARATOR, t) + 1;
    return hdfsNodePath.substring(s, t + 1);
  }

  // Should success when the localPath exists and the hdfsPath's parent paths are exists directories
  public static void uploadFileToHdfs(String localPath, String hdfsPath) throws Exception {
    try {
      FileSystem fs = FileSystem.get(conf);
      LOGGER.logInfo("[hadoop fs -put -f %s %s]", localPath, hdfsPath);
      fs.copyFromLocalFile(new Path(localPath), new Path(hdfsPath));
    } catch (PathNotFoundException e) {
      throw new NonTransientException("Path does not exist", e);
    } catch (Exception e) {
      if (e.getMessage().toLowerCase().contains("not a directory")) {
        throw new NonTransientException("Path is not a directory", e);
      } else {
        throw e;
      }
    }
  }

  // Should always success
  public static void removeDirInHdfs(String hdfsPath) throws Exception {
    try {
      FileSystem fs = FileSystem.get(conf);
      LOGGER.logInfo("[hadoop fs -rm -f -r -skipTrash %s]", hdfsPath);
      fs.delete(new Path(hdfsPath), true);
    } catch (PathNotFoundException ignored) {
    }
  }

  // Should success when the hdfsPath and its parent paths are directories
  // Note if parent directories do not exist, they will be created
  public static void makeDirInHdfs(String hdfsPath) throws Exception {
    try {
      FileSystem fs = FileSystem.get(conf);
      LOGGER.logInfo("[hadoop fs -mkdir -p %s]", hdfsPath);
      fs.mkdirs(new Path(hdfsPath));
    } catch (Exception e) {
      if (e.getMessage().toLowerCase().contains("not a directory")) {
        throw new NonTransientException("Path is not a directory", e);
      } else {
        throw e;
      }
    }
  }

  // Should always success
  public static Set<String> listDirInHdfs(String hdfsPath) throws Exception {
    Set<String> nodeNames = new HashSet<>();
    try {
      FileSystem fs = FileSystem.get(conf);
      LOGGER.logInfo("[hadoop fs -ls %s]", hdfsPath);
      RemoteIterator<LocatedFileStatus> files = fs.listFiles(new Path(hdfsPath), false);
      while (files.hasNext()) {
        nodeNames.add(files.next().getPath().getName());
      }
    } catch (FileNotFoundException ignored) {
    }
    return nodeNames;
  }

  // Should success when the hdfsPath exists
  private static FileStatus getFileStatusInHdfsInternal(String hdfsPath) throws Exception {
    try {
      FileSystem fs = FileSystem.get(conf);
      LOGGER.logInfo("[hadoop fs -stat %%Y %s]", hdfsPath);
      FileStatus fileStatus = fs.getFileStatus(new Path(hdfsPath));
      return fileStatus;
    } catch (PathNotFoundException e) {
      throw new NonTransientException("Path does not exist", e);
    }
  }

  public static FileStatus getFileStatusInHdfs(String hdfsPath) throws Exception {
    synchronized (resourceFileStatusCache) {
      if (!resourceFileStatusCache.containsKey(hdfsPath)) {
        resourceFileStatusCache.put(hdfsPath, getFileStatusInHdfsInternal(hdfsPath));
      }
      return resourceFileStatusCache.get(hdfsPath);
    }
  }

  // Should always success
  public static void killApplication(String applicationId) throws Exception {
    try {
      YarnClient yarnClient = YarnClient.createYarnClient();
      yarnClient.init(conf);
      yarnClient.start();
      LOGGER.logInfo("[yarn application -kill %s]", applicationId);
      yarnClient.killApplication(ConverterUtils.toApplicationId(applicationId));
      yarnClient.stop();
    } catch (ApplicationNotFoundException ignored) {
    } catch (Exception e) {
      if (e.getMessage().toLowerCase().contains("invalid applicationid")) {
        // ignored
      } else {
        throw e;
      }
    }
  }

  public static void submitApplication(
      ApplicationSubmissionContext appContext, UserDescriptor user) throws Throwable {
    UserGroupInformation ugi =
        UserGroupInformation.createRemoteUser(user.getName());
    // Need to start a new YarnClient for a new UGI, since its internal Hadoop RPC
    // reuse the UGI after YarnClient.start().
    try {
      ugi.doAs((PrivilegedExceptionAction<Void>) () -> {
        YarnClient yarnClient = YarnClient.createYarnClient();
        yarnClient.init(conf);
        yarnClient.start();
        yarnClient.submitApplication(appContext);
        yarnClient.stop();
        return null;
      });
    } catch (UndeclaredThrowableException e) {
      throw e.getCause();
    }
  }

  private static String getConfString(String confKey) throws Exception {
    return getConfString(confKey, null);
  }

  private static String getConfString(String confKey, String defaultConfValue) throws Exception {
    String confValue = conf.get(confKey, defaultConfValue);
    LOGGER.logInfo("[hdfs getconf -confKey %s] = [%s]", confKey, confValue);
    return confValue;
  }

  private static int getConfInt(String confKey, Integer defaultConfValue) throws Exception {
    return Integer.parseInt(getConfString(confKey, defaultConfValue.toString()));
  }

  public static int getResourceMinAllocation(ResourceType type) throws Exception {
    synchronized (resourceMinAllocationCache) {
      if (!resourceMinAllocationCache.containsKey(type)) {
        resourceMinAllocationCache.put(type, getResourceMinAllocationInternal(type));
      }
      return resourceMinAllocationCache.get(type);
    }
  }

  private static int getResourceMinAllocationInternal(ResourceType type) throws Exception {
    if (type == ResourceType.MEMORY) {
      return getConfInt(
          YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_MB,
          YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB);
    } else if (type == ResourceType.VIRTUAL_CORES) {
      return getConfInt(
          YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES,
          YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES);
    } else {
      throw new Exception(String.format("Not a valid ResourceType %s for MinAllocation", type));
    }
  }

  public static List<String> getCurrentAccessibleHostNames(YarnClient yarnClient) throws Exception {
    return getCurrentAccessibleHostNames(yarnClient, null);
  }

  public static List<String> getCurrentAccessibleHostNames(YarnClient yarnClient, String effectiveRequestNodeLabel) throws Exception {
    ArrayList<String> accessibleNodeHostNames = new ArrayList<>();

    List<NodeReport> clusterNodeReports = yarnClient.getNodeReports(NodeState.RUNNING);
    for (NodeReport node : clusterNodeReports) {
      String hostName = node.getNodeId().getHost();
      Set<String> nodeLabels = node.getNodeLabels();

      Boolean matchedWithRequestNodeLabel = false;

      Boolean fuzzyMatching = false;
      String effectiveRequestNodeLabelExact = effectiveRequestNodeLabel;
      if (effectiveRequestNodeLabel != null &&
          effectiveRequestNodeLabel.startsWith(NODE_LABEL_WILDCARD) &&
          effectiveRequestNodeLabel.endsWith(NODE_LABEL_WILDCARD)) {
        fuzzyMatching = true;
        effectiveRequestNodeLabelExact = effectiveRequestNodeLabel.substring(1, effectiveRequestNodeLabel.length() - 1);
      }

      if (fuzzyMatching) {
        // Fuzzy Matching
        // Note effectiveRequestNodeLabel may be ""
        for (String nodeLabelRaw : nodeLabels) {
          String[] nodeLabelSplitted = nodeLabelRaw.split(NODE_LABEL_DELIMITER);
          if (Arrays.asList(nodeLabelSplitted).contains(effectiveRequestNodeLabelExact)) {
            matchedWithRequestNodeLabel = true;
          }
        }
      } else {
        // Exact Matching
        // Note effectiveRequestNodeLabel may be "" or null
        if (nodeLabels.contains(effectiveRequestNodeLabelExact) ||
            (nodeLabels.size() == 0 && effectiveRequestNodeLabelExact == null)) {
          matchedWithRequestNodeLabel = true;
        }
      }

      if (matchedWithRequestNodeLabel) {
        accessibleNodeHostNames.add(hostName);
        logNodeInfo(node);
      }
    }

    LOGGER.logInfo(
        "Matched %s nodes with EffectiveRequestNodeLabel [%s] in this cluster",
        accessibleNodeHostNames.size(), effectiveRequestNodeLabel);

    return accessibleNodeHostNames;
  }

  public static HashSet<String> getLiveContainerIdsFromRM(String attemptId, String amContainerId) throws Exception {
    HashSet<String> containerIds = new HashSet<>();

    YarnClient yarnClient = YarnClient.createYarnClient();
    yarnClient.init(conf);
    yarnClient.start();
    List<ContainerReport> containerReports = yarnClient.getContainers(ConverterUtils.toApplicationAttemptId(attemptId));
    yarnClient.stop();

    // Since we at least has AM container, so we check whether the containerReports is reliable
    if (containerReports == null) {
      throw new Exception(
          String.format("Container reports of attempt %s is empty , but AM container exists",
              attemptId));
    }

    for (ContainerReport containerReport : containerReports) {
      if (containerReport.getContainerState() == ContainerState.COMPLETE) {
        continue;
      }
      containerIds.add(containerReport.getContainerId().toString());
    }

    if (!containerIds.contains(amContainerId)) {
      throw new Exception(
          String.format("Container reports of attempt %s does not contain AM container %s",
              attemptId, amContainerId));
    }
    containerIds.remove(amContainerId);

    return containerIds;
  }

  public static void logNodeInfo(List<NodeReport> nodes) {
    for (NodeReport node : nodes) {
      logNodeInfo(node);
    }
  }

  public static void logNodeInfo(NodeReport node) {
    try {
      String hostName = node.getNodeId().getHost();
      String label = "null";
      Set<String> labelSet = node.getNodeLabels();
      if (labelSet != null) {
        label = String.join(NODE_LABEL_DELIMITER, labelSet);
      }

      ResourceDescriptor rdCapacity = ResourceDescriptor.fromResource(node.getCapability());
      ResourceDescriptor rdUsed = ResourceDescriptor.fromResource(node.getUsed());
      LOGGER.logDebug("Got NodeReport from RM: " +
          "NodeState=" + node.getNodeState() +
          ", HostName=" + node.getNodeId().getHost() +
          ", RackName=" + node.getRackName() +
          ", Containers=" + node.getNumContainers() +
          ", NodeLabel=" + label +
          ", TotalMemory=" + rdCapacity.getMemoryMB() +
          ", UsedMemory=" + rdUsed.getMemoryMB() +
          ", TotalVirtualCores=" + rdCapacity.getCpuNumber() +
          ", TotalGPUs=" + rdCapacity.getGpuNumber() +
          ", UsedGPUs=" + rdUsed.getGpuNumber() +
          ", UsedGPUAttribute=" + rdUsed.getGpuAttribute());

    } catch (Exception e) {
      String nodeTag = "UNKNOWN";
      if (node.getNodeId() != null) {
        nodeTag = "HostName=" + node.getNodeId().getHost();
      } else if (!(node.getHttpAddress() == null || node.getHttpAddress().trim().length() == 0)) {
        nodeTag = "HttpAddress=" + node.getHttpAddress();
      }

      LOGGER.logWarning(e, "Failed to logNodeInfo for Node: %1$s", nodeTag);
    }
  }


  private static LocalResource convertToLocalResource(String hdfsPath, LocalResourceVisibility visibility) throws Exception {
    // Directory resource path must not end with /, otherwise localization will hang.
    hdfsPath = StringUtils.stripEnd(hdfsPath, HDFS_PATH_SEPARATOR);
    String extension = FilenameUtils.getExtension(hdfsPath).toLowerCase();
    LocalResourceType type;
    if (extension.equals(".zip") ||
        extension.equals(".tgz") ||
        extension.equals(".tar") ||
        extension.equals(".tar.gz")) {
      type = LocalResourceType.ARCHIVE;
    } else {
      type = LocalResourceType.FILE;
    }

    // Currently only HDFS URI is supported
    // Note Non APPLICATION LocalResourceVisibility may introduce conflicts if multiple
    // Applications' Containers on the same node write the same data in the resource directory.
    try {
      FileStatus fileStatus = getFileStatusInHdfs(hdfsPath);
      FileContext fileContext = FileContext.getFileContext(conf);
      return LocalResource.newInstance(
          ConverterUtils.getYarnUrlFromPath(fileContext
              .getDefaultFileSystem().resolvePath(fileStatus.getPath())),
          type, visibility, fileStatus.getLen(), fileStatus.getModificationTime());
    } catch (IllegalArgumentException e) {
      // hdfsPath may be from user, so it may be illegal.
      throw new NonTransientException("Path is illegal.", e);
    }
  }

  // By default, addToLocalResources is cached, need to use invalidateLocalResourcesCache to explicitly
  // invalidate out-of-date cache.
  public static void addToLocalResources(Map<String, LocalResource> localResources, String hdfsPath) throws Exception {
    hdfsPath = hdfsPath.trim();
    String localNodeName = getHdfsNodeName(hdfsPath);
    if (localResources.containsKey(localNodeName)) {
      throw new NonTransientException(String.format(
          "Duplicate file or directory names found in LocalResources: [%s], [%s]",
          localResources.get(localNodeName).getResource().getFile(),
          hdfsPath));
    }

    localResources.put(
        localNodeName,
        convertToLocalResource(hdfsPath, LocalResourceVisibility.APPLICATION));
  }

  public static void invalidateLocalResourcesCache() {
    synchronized (resourceFileStatusCache) {
      resourceFileStatusCache.clear();
    }
  }

  // All Resource should be Normalized before AddContainerRequest and RemoveContainerRequest,
  // since Allocated Container Resource is Normalized

  // Used for AddContainerRequest
  public static ContainerRequest convertToContainerRequest(
      ResourceDescriptor resource, Integer priority) throws Exception {
    Resource res = HadoopExtensions.normalize(resource).toResource();
    return new ContainerRequest(
        res, new String[]{}, new String[]{}, HadoopExtensions.toPriority(priority));
  }

  // Cannot specify nodelabel with rack or node
  public static ContainerRequest convertToContainerRequestWithHostName(
      ResourceDescriptor resource, Integer priority, String hostName) throws Exception {
    if (hostName != null) {
      Resource res = HadoopExtensions.normalize(resource).toResource();
      // Specify hostName with locality relaxed
      // In this way we can give a hint to RM to allocate the specified hostName instead of maybe hang forever
      return new ContainerRequest(
          res, new String[]{hostName}, new String[]{}, HadoopExtensions.toPriority(priority), true);
    } else {
      return convertToContainerRequest(resource, priority);
    }
  }

  // Cannot specify nodelabel with rack or node
  public static ContainerRequest convertToContainerRequestWithNodeLabel(
      ResourceDescriptor resource, Integer priority, String nodeLabel) throws Exception {
    if (nodeLabel != null) {
      Resource res = HadoopExtensions.normalize(resource).toResource();
      return new ContainerRequest(
          res, new String[]{}, new String[]{}, HadoopExtensions.toPriority(priority), true, nodeLabel);
    } else {
      return convertToContainerRequest(resource, priority);
    }
  }

  // Used for RemoveContainerRequest
  // REF:
  //  Internal ContainerRequest Map:
  //      Priority -> HostName -> Capability -> ResourceRequest
  public static ContainerRequest convertToContainerRequest(Container container) throws Exception {
    return convertToContainerRequestWithHostName(
        ResourceDescriptor.fromResource(
            container.getResource()), container.getPriority().getPriority(), container.getNodeId().getHost());
  }

  public static String getContainerLogHttpAddress(String nodeHttpAddress, String containerId, String user) {
    return String.format("http://%s/node/containerlogs/%s/%s/", nodeHttpAddress, containerId, user);
  }

  public static String getContainerLogNetworkPath(String nodeHostName, String amLogDirs, String containerId) {
    String trimmedLogDirs = amLogDirs.substring(0, amLogDirs.lastIndexOf("/"));
    if (Shell.WINDOWS) {
      String sharedLogDirs;
      if (amLogDirs.startsWith("D:")) {
        sharedLogDirs = trimmedLogDirs.replace("D:", "drived").replace("/", "\\");
      } else {
        sharedLogDirs = "drived" + trimmedLogDirs.replace("/", "\\");
      }
      return String.format("\\\\%s\\%s\\%s", nodeHostName, sharedLogDirs, containerId);
    } else {
      return String.format("%s:%s/%s", nodeHostName, trimmedLogDirs, containerId);
    }
  }

  public static String getAppCacheNetworkPath(String nodeHostName, String amLocalDirs) {
    if (Shell.WINDOWS) {
      String sharedLocalDirs;
      if (amLocalDirs.startsWith("D:")) {
        sharedLocalDirs = amLocalDirs.replace("D:", "drived").replace("/", "\\");
      } else {
        sharedLocalDirs = "drived" + amLocalDirs.replace("/", "\\");
      }
      return String.format("\\\\%s\\%s", nodeHostName, sharedLocalDirs);
    } else {
      return String.format("%s:%s", nodeHostName, amLocalDirs);
    }
  }
}