package io.choerodon.devops.app.service.impl;

import com.google.gson.Gson;
import io.choerodon.asgard.saga.feign.SagaClient;
import io.choerodon.core.convertor.ConvertHelper;
import io.choerodon.core.convertor.ConvertPageHelper;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.api.dto.ApplicationVersionAndCommitDTO;
import io.choerodon.devops.api.dto.ApplicationVersionRepDTO;
import io.choerodon.devops.api.dto.DeployEnvVersionDTO;
import io.choerodon.devops.api.dto.DeployInstanceVersionDTO;
import io.choerodon.devops.api.dto.DeployVersionDTO;
import io.choerodon.devops.app.service.ApplicationVersionService;
import io.choerodon.devops.app.service.PipelineService;
import io.choerodon.devops.domain.application.entity.ApplicationE;
import io.choerodon.devops.domain.application.entity.ApplicationInstanceE;
import io.choerodon.devops.domain.application.entity.ApplicationVersionE;
import io.choerodon.devops.domain.application.entity.ApplicationVersionValueE;
import io.choerodon.devops.domain.application.entity.DevopsEnvCommandE;
import io.choerodon.devops.domain.application.entity.DevopsEnvironmentE;
import io.choerodon.devops.domain.application.entity.DevopsGitlabCommitE;
import io.choerodon.devops.domain.application.entity.DevopsProjectConfigE;
import io.choerodon.devops.domain.application.entity.PipelineAppDeployE;
import io.choerodon.devops.domain.application.entity.PipelineE;
import io.choerodon.devops.domain.application.entity.PipelineTaskE;
import io.choerodon.devops.domain.application.entity.ProjectE;
import io.choerodon.devops.domain.application.entity.UserAttrE;
import io.choerodon.devops.domain.application.entity.iam.UserE;
import io.choerodon.devops.domain.application.handler.DevopsCiInvalidException;
import io.choerodon.devops.domain.application.repository.ApplicationInstanceRepository;
import io.choerodon.devops.domain.application.repository.ApplicationRepository;
import io.choerodon.devops.domain.application.repository.ApplicationVersionRepository;
import io.choerodon.devops.domain.application.repository.ApplicationVersionValueRepository;
import io.choerodon.devops.domain.application.repository.DevopsEnvCommandRepository;
import io.choerodon.devops.domain.application.repository.DevopsEnvironmentRepository;
import io.choerodon.devops.domain.application.repository.DevopsGitlabCommitRepository;
import io.choerodon.devops.domain.application.repository.DevopsProjectConfigRepository;
import io.choerodon.devops.domain.application.repository.IamRepository;
import io.choerodon.devops.domain.application.repository.PipelineAppDeployRepository;
import io.choerodon.devops.domain.application.repository.PipelineRepository;
import io.choerodon.devops.domain.application.repository.PipelineStageRepository;
import io.choerodon.devops.domain.application.repository.PipelineTaskRepository;
import io.choerodon.devops.domain.application.repository.UserAttrRepository;
import io.choerodon.devops.domain.application.valueobject.Organization;
import io.choerodon.devops.infra.common.util.ChartUtil;
import io.choerodon.devops.infra.common.util.FileUtil;
import io.choerodon.devops.infra.common.util.GitUserNameUtil;
import io.choerodon.devops.infra.common.util.TypeUtil;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Created by Zenger on 2018/4/3.
 */
@Service
public class ApplicationVersionServiceImpl implements ApplicationVersionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineServiceImpl.class);

    private static final String DESTPATH = "devops";
    private static final String STOREPATH = "stores";
    @Value("${services.gitlab.url}")
    private String gitlabUrl;
    @Autowired
    private ApplicationVersionRepository applicationVersionRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private IamRepository iamRepository;
    @Autowired
    private ApplicationVersionValueRepository applicationVersionValueRepository;
    @Autowired
    private ApplicationInstanceRepository applicationInstanceRepository;
    @Autowired
    private DevopsEnvironmentRepository devopsEnvironmentRepository;
    @Autowired
    private DevopsEnvCommandRepository devopsEnvCommandRepository;
    @Autowired
    private UserAttrRepository userAttrRepository;
    @Autowired
    private DevopsGitlabCommitRepository devopsGitlabCommitRepository;
    @Autowired
    private SagaClient sagaClient;
    @Autowired
    private DevopsProjectConfigRepository devopsProjectConfigRepository;
    @Autowired
    private PipelineAppDeployRepository appDeployRepository;
    @Autowired
    private PipelineTaskRepository taskRepository;
    @Autowired
    private PipelineStageRepository stageRepository;
    @Autowired
    private PipelineRepository pipelineRepository;
    @Autowired
    private ChartUtil chartUtil;
    @Autowired
    private PipelineService pipelineService;


    @Value("${services.helm.url}")
    private String helmUrl;

    private Gson gson = new Gson();

    /**
     * 方法中抛出runtime Exception而不是CommonException是为了返回非200的状态码。
     */
    @Override
    public void create(String image, String token, String version, String commit, MultipartFile files) {
        try {
            doCreate(image, token, version, commit, files);
        } catch (Exception e) {
            if (e instanceof CommonException) {
                throw new DevopsCiInvalidException(((CommonException) e).getCode(), e.getCause());
            }
            throw new DevopsCiInvalidException(e.getMessage(), e);
        }
    }

    private void doCreate(String image, String token, String version, String commit, MultipartFile files) {
        ApplicationE applicationE = applicationRepository.queryByToken(token);

        ApplicationVersionValueE applicationVersionValueE = new ApplicationVersionValueE();
        ApplicationVersionE applicationVersionE = new ApplicationVersionE();
        ProjectE projectE = iamRepository.queryIamProject(applicationE.getProjectE().getId());
        Organization organization = iamRepository.queryOrganizationById(projectE.getOrganization().getId());
        ApplicationVersionE newApplicationVersionE = applicationVersionRepository
                .queryByAppAndVersion(applicationE.getId(), version);
        applicationVersionE.initApplicationEById(applicationE.getId());
        applicationVersionE.setImage(image);
        applicationVersionE.setCommit(commit);
        applicationVersionE.setVersion(version);
        if (applicationE.getChartConfigE() != null) {
            DevopsProjectConfigE devopsProjectConfigE = devopsProjectConfigRepository.queryByPrimaryKey(applicationE.getChartConfigE().getId());
            helmUrl = devopsProjectConfigE.getConfig().getUrl();
        }

        applicationVersionE.setRepository(helmUrl.endsWith("/") ? helmUrl : helmUrl + "/" + organization.getCode() + "/" + projectE.getCode() + "/");
        String storeFilePath = STOREPATH + version;
        if (newApplicationVersionE != null) {
            return;
        }
        String destFilePath = DESTPATH + version;
        String path = FileUtil.multipartFileToFile(storeFilePath, files);
        //上传chart包到chartmusume
        chartUtil.uploadChart(organization.getCode(), projectE.getCode(), new File(path));
        FileUtil.unTarGZ(path, destFilePath);
        String values;
        try (FileInputStream fis = new FileInputStream(new File(Objects.requireNonNull(FileUtil.queryFileFromFiles(
                new File(destFilePath), "values.yaml")).getAbsolutePath()))) {
            values = FileUtil.replaceReturnString(fis, null);
        } catch (IOException e) {
            throw new CommonException(e);
        }

        try {
            FileUtil.checkYamlFormat(values);
        } catch (CommonException e) {
            throw new CommonException("The format of the values.yaml in the chart is invalid!", e);
        }
        applicationVersionValueE.setValue(values);
        try {
            applicationVersionE.initApplicationVersionValueE(applicationVersionValueRepository
                    .create(applicationVersionValueE).getId());
        } catch (Exception e) {
            throw new CommonException("error.version.insert", e);
        }
        applicationVersionE.initApplicationVersionReadmeV(FileUtil.getReadme(destFilePath));
        applicationVersionRepository.create(applicationVersionE);
        FileUtil.deleteDirectory(new File(destFilePath));
        FileUtil.deleteDirectory(new File(storeFilePath));
        //流水线
        LOGGER.info("=======开始触发自动部署："+new java.sql.Timestamp(System.currentTimeMillis()).toString());
        checkAutoDeploy(applicationVersionE);
        LOGGER.info("=======完成自动部署："+new java.sql.Timestamp(System.currentTimeMillis()).toString());
    }

    /**
     * 检测能够触发自动部署
     *
     * @param versionE
     */
    public void checkAutoDeploy(ApplicationVersionE versionE) {
        LOGGER.info("=======自动部署查001========1："+new java.sql.Timestamp(System.currentTimeMillis()).toString());
        ApplicationVersionE insertApplicationVersionE = applicationVersionRepository.queryByAppAndVersion(versionE.getApplicationE().getId(), versionE.getVersion());

        if (insertApplicationVersionE != null && insertApplicationVersionE.getVersion() != null) {
            LOGGER.info("=======自动部署查002========1："+new java.sql.Timestamp(System.currentTimeMillis()).toString());

            List<PipelineAppDeployE> appDeployEList = appDeployRepository.queryByAppId(insertApplicationVersionE.getApplicationE().getId())
                    .stream().map(deployE ->
                            filterAppDeploy(deployE, insertApplicationVersionE.getVersion())
                    ).collect(Collectors.toList());
            appDeployEList.removeAll(Collections.singleton(null));
            if (!appDeployEList.isEmpty()) {
                LOGGER.info("=======自动部署查003========1："+new java.sql.Timestamp(System.currentTimeMillis()).toString());
                List<Long> stageList = appDeployEList.stream()
                        .map(appDeploy -> taskRepository.queryByAppDeployId(appDeploy.getId()))
                        .filter(Objects::nonNull)
                        .map(PipelineTaskE::getStageId)
                        .distinct().collect(Collectors.toList());
                LOGGER.info("=======自动部署查004========1："+new java.sql.Timestamp(System.currentTimeMillis()).toString());
                List<Long> pipelineList = stageList.stream()
                        .map(stageId -> stageRepository.queryById(stageId).getPipelineId())
                        .distinct().collect(Collectors.toList());
                LOGGER.info("=======自动部署查005========1："+new java.sql.Timestamp(System.currentTimeMillis()).toString());
                pipelineList = pipelineList.stream()
                        .filter(pipelineId -> {
                            PipelineE pipelineE = pipelineRepository.queryById(pipelineId);
                            return pipelineE.getIsEnabled() == 1 && "auto".equals(pipelineE.getTriggerType());
                        }).collect(Collectors.toList());

                pipelineList.forEach(pipelineId -> {
                    if (pipelineService.checkDeploy(pipelineId)) {
                        LOGGER.info("autoDeploy: versionId:{}, version:{} pipelineId:{}", insertApplicationVersionE.getId(), insertApplicationVersionE.getVersion(), pipelineId);
                        LOGGER.info("=======自动部署查006========1："+new java.sql.Timestamp(System.currentTimeMillis()).toString());
                        pipelineService.executeAppDeploy(pipelineId);
                    }
                });
            }
        }
    }

    private PipelineAppDeployE filterAppDeploy(PipelineAppDeployE deployE, String version) {
        if (deployE.getTriggerVersion() == null || deployE.getTriggerVersion().isEmpty()) {
            return deployE;
        } else {
            List<String> list = Arrays.asList(deployE.getTriggerVersion().split(","));
            Optional<String> branch = list.stream().filter(version::contains).findFirst();
            if (branch.isPresent() && !branch.get().isEmpty()) {
                return deployE;
            }
            return null;
        }
    }

    @Override
    public List<ApplicationVersionRepDTO> listByAppId(Long appId, Boolean isPublish) {
        return ConvertHelper.convertList(
                applicationVersionRepository.listByAppId(appId, isPublish), ApplicationVersionRepDTO.class);
    }

    @Override
    public Page<ApplicationVersionRepDTO> listByAppIdAndParamWithPage(Long appId, Boolean isPublish, Long appVersionId, PageRequest pageRequest, String searchParam) {
        return ConvertPageHelper.convertPage(
                applicationVersionRepository.listByAppIdAndParamWithPage(appId, isPublish, appVersionId, pageRequest, searchParam), ApplicationVersionRepDTO.class);
    }

    @Override
    public List<ApplicationVersionRepDTO> listDeployedByAppId(Long projectId, Long appId) {
        return ConvertHelper.convertList(
                applicationVersionRepository.listDeployedByAppId(projectId, appId), ApplicationVersionRepDTO.class);
    }

    @Override
    public List<ApplicationVersionRepDTO> listByAppIdAndEnvId(Long projectId, Long appId, Long envId) {
        return ConvertHelper.convertList(
                applicationVersionRepository.listByAppIdAndEnvId(projectId, appId, envId),
                ApplicationVersionRepDTO.class);
    }

    @Override
    public Page<ApplicationVersionRepDTO> listApplicationVersionInApp(Long projectId, Long appId, PageRequest pageRequest, String searchParam) {
        UserAttrE userAttrE = userAttrRepository.queryById(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
        ProjectE projectE = iamRepository.queryIamProject(projectId);
        Boolean isProjectOwner = iamRepository.isProjectOwner(userAttrE.getIamUserId(), projectE);
        Page<ApplicationVersionE> applicationVersionEPage = applicationVersionRepository.listApplicationVersionInApp(
                projectId, appId, pageRequest, searchParam, isProjectOwner, userAttrE.getIamUserId());
        return ConvertPageHelper.convertPage(applicationVersionEPage, ApplicationVersionRepDTO.class);
    }

    @Override
    public List<ApplicationVersionRepDTO> getUpgradeAppVersion(Long projectId, Long appVersionId) {
        applicationVersionRepository.checkProIdAndVerId(projectId, appVersionId);
        return ConvertHelper.convertList(
                applicationVersionRepository.selectUpgradeVersions(appVersionId),
                ApplicationVersionRepDTO.class);
    }

    @Override
    public DeployVersionDTO listDeployVersions(Long appId) {
        ApplicationVersionE applicationVersionE = applicationVersionRepository.getLatestVersion(appId);
        DeployVersionDTO deployVersionDTO = new DeployVersionDTO();
        List<DeployEnvVersionDTO> deployEnvVersionDTOS = new ArrayList<>();
        if (applicationVersionE != null) {
            Map<Long, List<ApplicationInstanceE>> envInstances = applicationInstanceRepository.listByAppId(appId).stream().filter(applicationInstanceE -> applicationInstanceE.getCommandId() != null)
                    .collect(Collectors.groupingBy(t -> t.getDevopsEnvironmentE().getId()));
            if (!envInstances.isEmpty()) {
                envInstances.forEach((key, value) -> {
                    DevopsEnvironmentE devopsEnvironmentE = devopsEnvironmentRepository.queryById(key);
                    DeployEnvVersionDTO deployEnvVersionDTO = new DeployEnvVersionDTO();
                    deployEnvVersionDTO.setEnvName(devopsEnvironmentE.getName());
                    List<DeployInstanceVersionDTO> deployInstanceVersionDTOS = new ArrayList<>();
                    Map<Long, List<ApplicationInstanceE>> versionInstances = value.stream().collect(Collectors.groupingBy(t -> {
                        DevopsEnvCommandE devopsEnvCommandE = devopsEnvCommandRepository.query(t.getCommandId());
                        return devopsEnvCommandE.getObjectVersionId();
                    }));
                    if (!versionInstances.isEmpty()) {
                        versionInstances.forEach((newkey, newvalue) -> {
                            ApplicationVersionE newApplicationVersionE = applicationVersionRepository.query(newkey);
                            DeployInstanceVersionDTO deployInstanceVersionDTO = new DeployInstanceVersionDTO();
                            deployInstanceVersionDTO.setDeployVersion(newApplicationVersionE.getVersion());
                            deployInstanceVersionDTO.setInstanceCount(newvalue.size());
                            if (newApplicationVersionE.getId() < applicationVersionE.getId()) {
                                deployInstanceVersionDTO.setUpdate(true);
                            }
                            deployInstanceVersionDTOS.add(deployInstanceVersionDTO);
                        });
                    }
                    deployEnvVersionDTO.setDeployIntanceVersionDTO(deployInstanceVersionDTOS);
                    deployEnvVersionDTOS.add(deployEnvVersionDTO);
                });

                deployVersionDTO.setLatestVersion(applicationVersionE.getVersion());
                deployVersionDTO.setDeployEnvVersionDTO(deployEnvVersionDTOS);
            }
        }
        return deployVersionDTO;
    }

    @Override
    public String queryVersionValue(Long appVersionId) {
        ApplicationVersionE applicationVersionE = applicationVersionRepository.query(appVersionId);
        ApplicationVersionValueE applicationVersionValueE = applicationVersionValueRepository.query(applicationVersionE.getApplicationVersionValueE().getId());
        return applicationVersionValueE.getValue();
    }

    @Override
    public ApplicationVersionRepDTO queryById(Long appVersionId) {
        return ConvertHelper.convert(applicationVersionRepository.query(appVersionId), ApplicationVersionRepDTO.class);
    }

    @Override
    public List<ApplicationVersionRepDTO> listByAppVersionIds(List<Long> appVersionIds) {
        return ConvertHelper.convertList(applicationVersionRepository.listByAppVersionIds(appVersionIds), ApplicationVersionRepDTO.class);
    }

    @Override
    public List<ApplicationVersionAndCommitDTO> listByAppIdAndBranch(Long appId, String branch) {
        List<ApplicationVersionE> applicationVersionES = applicationVersionRepository.listByAppIdAndBranch(appId, branch);
        ApplicationE applicationE = applicationRepository.query(appId);
        ProjectE projectE = iamRepository.queryIamProject(applicationE.getProjectE().getId());
        Organization organization = iamRepository.queryOrganizationById(projectE.getOrganization().getId());
        List<ApplicationVersionAndCommitDTO> applicationVersionAndCommitDTOS = new ArrayList<>();
        applicationVersionES.forEach(applicationVersionE -> {
            ApplicationVersionAndCommitDTO applicationVersionAndCommitDTO = new ApplicationVersionAndCommitDTO();
            DevopsGitlabCommitE devopsGitlabCommitE = devopsGitlabCommitRepository.queryByShaAndRef(applicationVersionE.getCommit(), branch);
            UserE userE = iamRepository.queryUserByUserId(devopsGitlabCommitE.getUserId());
            applicationVersionAndCommitDTO.setAppName(applicationE.getName());
            applicationVersionAndCommitDTO.setCommit(applicationVersionE.getCommit());
            applicationVersionAndCommitDTO.setCommitContent(devopsGitlabCommitE.getCommitContent());
            applicationVersionAndCommitDTO.setCommitUserImage(userE == null ? null : userE.getImageUrl());
            applicationVersionAndCommitDTO.setCommitUserName(userE == null ? null : userE.getRealName());
            applicationVersionAndCommitDTO.setVersion(applicationVersionE.getVersion());
            applicationVersionAndCommitDTO.setCreateDate(applicationVersionE.getCreationDate());
            applicationVersionAndCommitDTO.setCommitUrl(gitlabUrl + "/"
                    + organization.getCode() + "-" + projectE.getCode() + "/"
                    + applicationE.getCode() + ".git");
            applicationVersionAndCommitDTOS.add(applicationVersionAndCommitDTO);

        });
        return applicationVersionAndCommitDTOS;
    }

    @Override
    public Boolean queryByPipelineId(Long pipelineId, String branch) {
        return applicationVersionRepository.queryByPipelineId(pipelineId, branch) != null;
    }

    @Override
    public String queryValueById(Long projectId, Long appId) {
        return applicationVersionRepository.queryValueById(appId);
    }

    @Override
    public ApplicationVersionRepDTO queryByAppAndVersion(Long appId, String version) {
        return ConvertHelper.convert(applicationVersionRepository.queryByAppAndCode(appId, version), ApplicationVersionRepDTO.class);
    }


}
