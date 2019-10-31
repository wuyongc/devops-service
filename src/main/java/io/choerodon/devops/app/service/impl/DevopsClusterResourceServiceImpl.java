package io.choerodon.devops.app.service.impl;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.api.vo.ClusterResourceVO;
import io.choerodon.devops.api.vo.ContainerVO;
import io.choerodon.devops.api.vo.DevopsEnvPodVO;
import io.choerodon.devops.api.vo.DevopsPrometheusVO;
import io.choerodon.devops.app.service.*;
import io.choerodon.devops.infra.dto.*;
import io.choerodon.devops.infra.enums.CertificationStatus;
import io.choerodon.devops.infra.enums.ClusterResourceStatus;
import io.choerodon.devops.infra.enums.ClusterResourceType;
import io.choerodon.devops.infra.feign.operator.GitlabServiceClientOperator;
import io.choerodon.devops.infra.handler.ClusterConnectionHandler;
import io.choerodon.devops.infra.mapper.DevopsCertManagerRecordMapper;
import io.choerodon.devops.infra.mapper.DevopsCertificationMapper;
import io.choerodon.devops.infra.mapper.DevopsClusterResourceMapper;
import io.choerodon.devops.infra.mapper.DevopsPrometheusMapper;
import io.choerodon.devops.infra.util.ConvertUtils;

/**
 * @author zhaotianxin
 * @since 2019/10/29
 */
@Service
public class DevopsClusterResourceServiceImpl implements DevopsClusterResourceService {
    @Autowired
    private DevopsClusterResourceMapper devopsClusterResourceMapper;
    @Autowired
    private AgentCommandService agentCommandService;
    @Autowired
    private DevopsCertificationMapper devopsCertificationMapper;
    @Autowired
    private DevopsCertManagerRecordMapper devopsCertManagerRecordMapper;
    @Autowired
    private DevopsPrometheusMapper devopsPrometheusMapper;

    @Autowired
    private DevopsClusterResourceService devopsClusterResourceService;

    @Autowired
    private ComponentReleaseService componentReleaseService;

    @Autowired
    private AppServiceInstanceService appServiceInstanceService;

    @Autowired
    private DevopsEnvironmentService devopsEnvironmentService;

    @Autowired
    private DevopsEnvCommandService devopsEnvCommandService;

    @Autowired
    private UserAttrService userAttrService;

    @Autowired
    private ClusterConnectionHandler clusterConnectionHandler;

    @Autowired
    private DevopsEnvFileResourceService devopsEnvFileResourceService;

    @Autowired
    private GitlabServiceClientOperator gitlabServiceClientOperator;

    @Autowired
    private DevopsClusterService devopsClusterService;

    @Autowired
    private DevopsEnvPodService devopsEnvPodService;
    private static final String PROMETHEUS_PREFIX = "prometheus-";

    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_FAIL = "fail";
    private static final String STATUS_CREATED = "created";
    private static final String STATUS_SUCCESSED = "success";
    private static final String STATUS_CHECK_FAIL = "check_fail";
    private static final String GRAFANA_NODE = "/d/choerodon-default-node/jie-dian";
    private static final String GRAFANA_CLUSTER = "/d/choerodon-default-cluster/ji-qun";

    @Override
    public void baseCreate(DevopsClusterResourceDTO devopsClusterResourceDTO) {
        if (devopsClusterResourceMapper.insertSelective(devopsClusterResourceDTO) != 1) {
            throw new CommonException("error.insert.cluster.resource");
        }
    }

    public void baseUpdate(DevopsClusterResourceDTO devopsClusterResourceDTO) {
        if (devopsClusterResourceMapper.updateByPrimaryKeySelective(devopsClusterResourceDTO) != 1) {
            throw new CommonException("error.update.cluster.resource");
        }

    }

    @Override
    public void operateCertManager(Long clusterId, String status, String error) {
        DevopsClusterResourceDTO devopsClusterResourceDTO = new DevopsClusterResourceDTO();
        devopsClusterResourceDTO.setType(ClusterResourceType.CERTMANAGER.getType());
        devopsClusterResourceDTO.setClusterId(clusterId);
        DevopsClusterResourceDTO clusterResourceDTO = devopsClusterResourceMapper.queryByOptions(devopsClusterResourceDTO);
        if (!ObjectUtils.isEmpty(clusterResourceDTO)) {
            DevopsCertManagerRecordDTO devopsCertManagerRecordDTO = devopsCertManagerRecordMapper.selectByPrimaryKey(clusterResourceDTO.getObjectId());
            devopsCertManagerRecordDTO.setError(error);
            devopsCertManagerRecordDTO.setStatus(status);
            devopsCertManagerRecordMapper.updateByPrimaryKeySelective(devopsCertManagerRecordDTO);
        } else {
            DevopsCertManagerRecordDTO devopsCertManagerRecordDTO = new DevopsCertManagerRecordDTO();
            devopsCertManagerRecordDTO.setStatus(status);
            devopsCertManagerRecordDTO.setError(error);
            if (ObjectUtils.isEmpty(status)) {
                devopsCertManagerRecordDTO.setStatus(ClusterResourceStatus.PROCESSING.getStatus());
            }
            devopsCertManagerRecordMapper.insertSelective(devopsCertManagerRecordDTO);
            // 插入数据
            devopsClusterResourceDTO.setObjectId(devopsCertManagerRecordDTO.getId());
            devopsClusterResourceDTO.setClusterId(clusterId);
            baseCreate(devopsClusterResourceDTO);
            // 让agent创建cert-mannager
            agentCommandService.createCertManager(clusterId);
        }
    }

    @Override
    public Boolean deleteCertManager(Long clusterId) {
        if (!checkCertManager(clusterId)) {
            return false;
        }
        DevopsClusterResourceDTO devopsClusterResourceDTO = queryByClusterIdAndType(clusterId, ClusterResourceType.CERTMANAGER.getType());
        DevopsCertManagerRecordDTO devopsCertManagerRecordDTO = devopsCertManagerRecordMapper.selectByPrimaryKey(devopsClusterResourceDTO.getObjectId());
        devopsCertManagerRecordDTO.setStatus(ClusterResourceStatus.PROCESSING.getStatus());
        devopsCertManagerRecordMapper.updateByPrimaryKey(devopsCertManagerRecordDTO);
        agentCommandService.unloadCertManager(clusterId);
        return true;
    }

    public Boolean checkValidity(Date date, Date validFrom, Date validUntil) {
        return validFrom != null && validUntil != null
                && date.after(validFrom) && date.before(validUntil);
    }

    @Override
    public Boolean checkCertManager(Long clusterId) {
        List<CertificationDTO> certificationDTOS = devopsCertificationMapper.listClusterCertification(clusterId);
        if (CollectionUtils.isEmpty(certificationDTOS)) {
            return true;
        }
        Set<Long> ids = new HashSet<>();
        certificationDTOS.forEach(dto -> {
            boolean is_faile = CertificationStatus.FAILED.getStatus().equals(dto.getStatus()) || CertificationStatus.OVERDUE.getStatus().equals(dto.getStatus());
            if (!is_faile) {
                if (CertificationStatus.ACTIVE.getStatus().equals(dto.getStatus())) {
                    if (!checkValidity(new Date(), dto.getValidFrom(), dto.getValidUntil())) {
                        dto.setStatus(CertificationStatus.OVERDUE.getStatus());
                        CertificationDTO certificationDTO = new CertificationDTO();
                        certificationDTO.setId(dto.getId());
                        certificationDTO.setStatus(CertificationStatus.OVERDUE.getStatus());
                        certificationDTO.setObjectVersionNumber(dto.getObjectVersionNumber());
                        devopsCertificationMapper.updateByPrimaryKeySelective(certificationDTO);
                    }
                } else {
                    ids.add(dto.getId());
                }
            }
        });
        if (CollectionUtils.isEmpty(ids)) {
            return true;
        }
        return false;
    }

    @Override
    public DevopsClusterResourceDTO queryByClusterIdAndConfigId(Long clusterId, Long configId) {
        DevopsClusterResourceDTO devopsClusterResourceDTO = devopsClusterResourceMapper.queryByClusterIdAndTypeAndConfigId(clusterId, ClusterResourceType.PROMETHEUS.getType(), configId);
        return devopsClusterResourceDTO;
    }


    @Override
    public DevopsClusterResourceDTO queryByClusterIdAndType(Long clusterId, String type) {
        DevopsClusterResourceDTO devopsClusterResourceDTO = devopsClusterResourceMapper.queryByClusterIdAndType(clusterId, type);
        return devopsClusterResourceDTO;
    }

    @Override
    public List<ClusterResourceVO> listClusterResource(Long clusterId, Long projectId) {
        List<ClusterResourceVO> list = new ArrayList<>();
        // 查询cert-manager 状态
        DevopsClusterResourceDTO devopsClusterResourceDTO = queryByClusterIdAndType(clusterId, ClusterResourceType.CERTMANAGER.getType());
        ClusterResourceVO clusterConfigVO = new ClusterResourceVO();
        if (ObjectUtils.isEmpty(devopsClusterResourceDTO)) {
            clusterConfigVO.setStatus(ClusterResourceStatus.UNINSTALL.getStatus());
        } else {
            DevopsCertManagerRecordDTO devopsCertManagerRecordDTO = devopsCertManagerRecordMapper.selectByPrimaryKey(devopsClusterResourceDTO.getObjectId());
            if (!ObjectUtils.isEmpty(devopsCertManagerRecordDTO)) {
                clusterConfigVO.setStatus(devopsCertManagerRecordDTO.getStatus());
                clusterConfigVO.setMessage(devopsCertManagerRecordDTO.getError());
            }
            clusterConfigVO.setType(ClusterResourceType.CERTMANAGER.getType());
        }
        list.add(clusterConfigVO);
        // 查询prometheus 的状态和信息
        DevopsClusterResourceDTO prometheus = devopsClusterResourceMapper.queryByClusterIdAndType(clusterId, ClusterResourceType.PROMETHEUS.getType());
        Long configId = prometheus.getConfigId();
        ClusterResourceVO clusterResourceVO = queryPrometheusStatus(projectId, clusterId);
        list.add(clusterResourceVO);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unloadCertManager(Long clusterId) {
        List<CertificationDTO> certificationDTOS = devopsCertificationMapper.listClusterCertification(clusterId);
        if (!CollectionUtils.isEmpty(certificationDTOS)) {
            certificationDTOS.forEach(v -> {
                devopsCertificationMapper.deleteByPrimaryKey(v.getId());
            });
        }
        DevopsClusterResourceDTO devopsClusterResourceDTO = queryByClusterIdAndType(clusterId, ClusterResourceType.CERTMANAGER.getType());
        devopsCertManagerRecordMapper.deleteByPrimaryKey(devopsClusterResourceDTO.getObjectId());
        devopsClusterResourceMapper.deleteByPrimaryKey(devopsClusterResourceDTO.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrUpdate(Long clusterId, DevopsPrometheusVO devopsPrometheusVO) {
        DevopsClusterDTO devopsClusterDTO = devopsClusterService.baseQuery(clusterId);
        DevopsClusterResourceDTO devopsClusterResourceDTO = devopsClusterResourceMapper.queryByClusterIdAndType(clusterId, ClusterResourceType.PROMETHEUS.getType());
        if (devopsClusterDTO.getSystemEnvId() == null) {
            throw new CommonException("error.cluster.SystemEnvId");
        }
        DevopsPrometheusDTO devopsPrometheusDTO = prometheusVoToDto(devopsPrometheusVO);
        if (ObjectUtils.isEmpty(devopsPrometheusVO.getId())) {
            AppServiceInstanceDTO appServiceInstanceDTO = componentReleaseService.createReleaseForPrometheus(devopsClusterDTO.getSystemEnvId(), devopsPrometheusDTO);
            if (devopsPrometheusMapper.insertSelective(devopsPrometheusDTO) != 1) {
                throw new CommonException("error.inster.prometheus");
            }
            devopsPrometheusDTO.setId(devopsPrometheusDTO.getId());

            devopsClusterResourceDTO.setClusterId(clusterId);
            devopsClusterResourceDTO.setConfigId(devopsPrometheusDTO.getId());
            devopsClusterResourceDTO.setObjectId(appServiceInstanceDTO.getId());
            devopsClusterResourceDTO.setName(devopsClusterDTO.getName());
            devopsClusterResourceDTO.setCode(devopsClusterDTO.getCode());
            devopsClusterResourceDTO.setType(ClusterResourceType.PROMETHEUS.getType());
            devopsClusterResourceService.baseCreate(devopsClusterResourceDTO);
        } else {
            AppServiceInstanceDTO appServiceInstanceDTO = componentReleaseService.updateReleaseForPrometheus(devopsPrometheusDTO, devopsClusterResourceDTO.getObjectId(), devopsClusterDTO.getSystemEnvId());
            devopsClusterResourceDTO.setObjectId(appServiceInstanceDTO.getId());
            if (devopsPrometheusMapper.updateByPrimaryKey(devopsPrometheusDTO) != 1) {
                throw new CommonException("error.update.prometheus");
            }
            devopsClusterResourceDTO.setObjectId(appServiceInstanceDTO.getId());
            devopsClusterResourceService.baseUpdate(devopsClusterResourceDTO);

        }

    }

    @Override
    public DevopsPrometheusVO queryPrometheus(Long clusterId) {
        DevopsClusterResourceDTO devopsClusterResourceDTO = devopsClusterResourceMapper.queryByClusterIdAndType(clusterId, ClusterResourceType.PROMETHEUS.getType());
        DevopsPrometheusDTO devopsPrometheusDTO = devopsPrometheusMapper.selectByPrimaryKey(devopsClusterResourceDTO.getConfigId());
        DevopsPrometheusVO devopsPrometheusVO = ConvertUtils.convertObject(devopsPrometheusDTO, DevopsPrometheusVO.class);
        return devopsPrometheusVO;
    }

    @Override
    public ClusterResourceVO queryDeployProcess(Long clusterId) {
        DevopsClusterResourceDTO devopsClusterResourceDTO = devopsClusterResourceMapper.queryByClusterIdAndType(clusterId, ClusterResourceType.PROMETHEUS.getType());
        AppServiceInstanceDTO appServiceInstanceDTO = appServiceInstanceService.baseQuery(devopsClusterResourceDTO.getObjectId());
        DevopsEnvCommandDTO devopsEnvCommandDTO = devopsEnvCommandService.baseQuery(appServiceInstanceDTO.getCommandId());

        List<DevopsEnvPodVO> devopsEnvPodDTOS = ConvertUtils.convertList(devopsEnvPodService.baseListByInstanceId(appServiceInstanceDTO.getId()), DevopsEnvPodVO.class);
        //查询pod状态
        devopsEnvPodDTOS.stream().forEach(devopsEnvPodVO -> {
            devopsEnvPodService.fillContainers(devopsEnvPodVO);
        });

        List<ContainerVO> readyPod = new ArrayList<>();
        ClusterResourceVO clusterResourceVO = new ClusterResourceVO();
        clusterResourceVO.setType(ClusterResourceType.PROMETHEUS.getType());
        if (!ObjectUtils.isEmpty(devopsEnvCommandDTO.getSha())) {
            clusterResourceVO.setStatus(STATUS_CREATED);
        }
        if (appServiceInstanceDTO.getStatus().equals(STATUS_RUNNING)) {
            clusterResourceVO.setStatus(STATUS_RUNNING);
            //ready=true的pod大于1就是可用的
            devopsEnvPodDTOS.stream().forEach(devopsEnvPodVO -> {
                if (devopsEnvPodVO.getReady() == true) {
                    readyPod.addAll(devopsEnvPodVO.getContainers().stream().filter(pod -> pod.getReady() == true).collect(Collectors.toList()));
                }
            });
            if (readyPod.size() >= 1) {
                clusterResourceVO.setStatus(STATUS_SUCCESSED);
            }

        } else {
            clusterResourceVO.setMessage(devopsEnvCommandDTO.getError());
            clusterResourceVO.setStatus(STATUS_FAIL);
        }


        return clusterResourceVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePrometheus(Long clusterId) {
        DevopsClusterResourceDTO devopsClusterResourceDTO = devopsClusterResourceMapper.queryByClusterIdAndType(clusterId, ClusterResourceType.PROMETHEUS.getType());
        componentReleaseService.deleteReleaseForComponent(devopsClusterResourceDTO.getObjectId());
        if (devopsPrometheusMapper.deleteByPrimaryKey(devopsClusterResourceDTO.getConfigId()) != 1) {
            throw new CommonException("error.delete.prometheus");
        }
        if (devopsClusterResourceMapper.delete(devopsClusterResourceDTO) != 1) {
            throw new CommonException("error.delete.devopsClusterResource");
        }
    }

    @Override
    public ClusterResourceVO queryPrometheusStatus(Long projectId, Long clusterId) {
        ClusterResourceVO clusterResourceVO = new ClusterResourceVO();

        DevopsClusterResourceDTO devopsClusterResourceDTO = devopsClusterResourceMapper.queryByClusterIdAndType(clusterId, ClusterResourceType.PROMETHEUS.getType());
        if (devopsClusterResourceDTO.getConfigId() == null) {
            clusterResourceVO.setStatus(ClusterResourceStatus.UNINSTALL.getStatus());
            return clusterResourceVO;
        }
        clusterResourceVO = queryDeployProcess(clusterId);

        //create
        switch (clusterResourceVO.getStatus()) {
            case STATUS_SUCCESSED:
                clusterResourceVO.setStatus(ClusterResourceStatus.AVAILABLE.getStatus());
                break;
            case STATUS_CREATED:
                clusterResourceVO.setStatus(ClusterResourceStatus.PROCESSING.getStatus());
                break;
            case STATUS_RUNNING:
                clusterResourceVO.setStatus(ClusterResourceStatus.PROCESSING.getStatus());
                break;
            case STATUS_FAIL:
                clusterResourceVO.setStatus(ClusterResourceStatus.DISABLED.getStatus());
                clusterResourceVO.setMessage(clusterResourceVO.getMessage());
                break;
            default:
                clusterResourceVO.setStatus(ClusterResourceStatus.DISABLED.getStatus());
                clusterResourceVO.setMessage(clusterResourceVO.getMessage());
        }

        clusterResourceVO.setType(ClusterResourceType.PROMETHEUS.getType());
        return clusterResourceVO;
    }

    private DevopsPrometheusDTO prometheusVoToDto(DevopsPrometheusVO prometheusVo) {
        DevopsPrometheusDTO devopsPrometheusDTO = new DevopsPrometheusDTO();
        BeanUtils.copyProperties(prometheusVo, devopsPrometheusDTO);
        return devopsPrometheusDTO;
    }

    @Override
    public String getGrafanaUrl(Long clusterId, String type) {
        DevopsClusterResourceDTO clusterResourceDTO = queryByClusterIdAndType(clusterId, ClusterResourceType.PROMETHEUS.getType());
        DevopsPrometheusDTO devopsPrometheusDTO = devopsPrometheusMapper.selectByPrimaryKey(clusterResourceDTO.getConfigId());
        String grafanaType = type.equals("node") ? GRAFANA_NODE : GRAFANA_CLUSTER;
        return String.format("%s%s%s", "http://", devopsPrometheusDTO.getGrafanaDomain(), grafanaType);
    }

}