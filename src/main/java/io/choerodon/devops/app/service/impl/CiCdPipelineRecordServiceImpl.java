package io.choerodon.devops.app.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import io.choerodon.core.exception.CommonException;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;
import io.choerodon.devops.api.vo.*;
import io.choerodon.devops.app.service.*;
import io.choerodon.devops.infra.dto.*;
import io.choerodon.devops.infra.dto.gitlab.CommitDTO;
import io.choerodon.devops.infra.dto.workflow.DevopsPipelineDTO;
import io.choerodon.devops.infra.enums.PipelineStatus;
import io.choerodon.devops.infra.enums.WorkFlowStatus;
import io.choerodon.devops.infra.feign.operator.GitlabServiceClientOperator;
import io.choerodon.devops.infra.feign.operator.WorkFlowServiceOperator;
import io.choerodon.devops.infra.mapper.*;
import io.choerodon.devops.infra.util.ConvertUtils;
import io.choerodon.devops.infra.util.GenerateUUID;
import io.choerodon.devops.infra.util.TypeUtil;

@Service
public class CiCdPipelineRecordServiceImpl implements CiCdPipelineRecordService {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private DevopsCiPipelineRecordService devopsCiPipelineRecordService;

    @Autowired
    private DevopsCdPipelineRecordService devopsCdPipelineRecordService;

    @Autowired
    private DevopsCdStageRecordMapper devopsCdStageRecordMapper;
    @Autowired
    private DevopsCdJobRecordMapper devopsCdJobRecordMapper;
    @Autowired
    @Lazy
    private DevopsCdJobRecordService devopsCdJobRecordService;
    @Autowired
    private DevopsCdStageRecordService devopsCdStageRecordService;

    @Autowired
    @Lazy
    private DevopsCdPipelineService devopsCdPipelineService;

    @Autowired
    private WorkFlowServiceOperator workFlowServiceOperator;

    @Autowired
    private DevopsCiPipelineService devopsCiPipelineService;

    @Autowired
    private DevopsCiStageMapper devopsCiStageMapper;

    @Autowired
    private AppServiceMapper appServiceMapper;

    @Autowired
    private GitlabServiceClientOperator gitlabServiceClientOperator;

    @Autowired
    private DevopsGitlabCommitMapper devopsGitlabCommitMapper;


    @Override
    public CiCdPipelineRecordVO queryPipelineRecordDetails(Long projectId, Long gitlabPipelineId) {
        CiCdPipelineRecordVO ciCdPipelineRecordVO = new CiCdPipelineRecordVO();
        DevopsCiPipelineRecordVO devopsCiPipelineRecordVO = devopsCiPipelineRecordService.queryPipelineRecordDetails(projectId, gitlabPipelineId);
        DevopsCdPipelineRecordVO devopsCdPipelineRecordVO = devopsCdPipelineRecordService.queryPipelineRecordDetails(projectId, gitlabPipelineId);
        //ci和cd都有记录
        List<StageRecordVO> stageRecordVOS = new ArrayList<>();
        if (devopsCiPipelineRecordVO != null && devopsCdPipelineRecordVO != null) {
            stageRecordVOS.addAll(devopsCiPipelineRecordVO.getStageRecordVOList());
            stageRecordVOS.addAll(devopsCdPipelineRecordVO.getDevopsCdStageRecordVOS());
            ciCdPipelineRecordVO.setStageRecordVOS(stageRecordVOS);
            ciCdPipelineRecordVO.setGitlabPipelineId(devopsCiPipelineRecordVO.getGitlabPipelineId());
            //计算记录的状态
            if (PipelineStatus.SUCCESS.toValue().equals(devopsCiPipelineRecordVO.getStatus())) {
                ciCdPipelineRecordVO.setStatus(devopsCdPipelineRecordVO.getStatus());
            } else {
                ciCdPipelineRecordVO.setStatus(devopsCdPipelineRecordVO.getStatus());
            }
            ciCdPipelineRecordVO.setCommit(devopsCiPipelineRecordVO.getCommit());
            ciCdPipelineRecordVO.setGitlabTriggerRef(devopsCiPipelineRecordVO.getGitlabTriggerRef());
            ciCdPipelineRecordVO.setCiCdPipelineVO(ConvertUtils.convertObject(devopsCiPipelineRecordVO.getDevopsCiPipelineVO(), CiCdPipelineVO.class));
        }
        //纯ci
        if (devopsCiPipelineRecordVO != null && devopsCdPipelineRecordVO == null) {
            stageRecordVOS.addAll(devopsCiPipelineRecordVO.getStageRecordVOList());
            ciCdPipelineRecordVO.setCommit(devopsCiPipelineRecordVO.getCommit());
            ciCdPipelineRecordVO.setGitlabTriggerRef(devopsCiPipelineRecordVO.getGitlabTriggerRef());
            ciCdPipelineRecordVO.setStageRecordVOS(stageRecordVOS);
            ciCdPipelineRecordVO.setGitlabPipelineId(devopsCiPipelineRecordVO.getGitlabPipelineId());
            ciCdPipelineRecordVO.setStatus(devopsCiPipelineRecordVO.getStatus());
            ciCdPipelineRecordVO.setCiCdPipelineVO(ConvertUtils.convertObject(devopsCiPipelineRecordVO.getDevopsCiPipelineVO(), CiCdPipelineVO.class));
        }
        //纯cd
        if (devopsCiPipelineRecordVO == null && devopsCdPipelineRecordVO != null) {
            stageRecordVOS.addAll(devopsCdPipelineRecordVO.getDevopsCdStageRecordVOS());
            ciCdPipelineRecordVO.setStageRecordVOS(stageRecordVOS);
            ciCdPipelineRecordVO.setStatus(devopsCdPipelineRecordVO.getStatus());
            ciCdPipelineRecordVO.setCiCdPipelineVO(ConvertUtils.convertObject(devopsCdPipelineRecordVO.getCiCdPipelineVO(), CiCdPipelineVO.class));
        }
        return ciCdPipelineRecordVO;
    }

    @Override
    public void retryPipeline(Long projectId, Long cdPipelineRecordId, Long gitlabPipelineId, Long gitlabProjectId) {
        if (ObjectUtils.isEmpty(cdPipelineRecordId)) {
            devopsCiPipelineRecordService.retry(projectId, gitlabPipelineId, gitlabProjectId);
        } else {
            retryCdPipeline(projectId, cdPipelineRecordId);
        }
    }

    @Transactional
    public void retryCdPipeline(Long projectId, Long cdPipelineRecordId) {
        // 0.1 更新business key
        DevopsCdPipelineRecordDTO devopsCdPipelineRecordDTO = devopsCdPipelineRecordService.queryById(cdPipelineRecordId);
        devopsCdPipelineRecordDTO.setBusinessKey(GenerateUUID.generateUUID());
        devopsCdPipelineRecordDTO.setStatus(PipelineStatus.RUNNING.toValue());
        devopsCdPipelineRecordService.update(devopsCdPipelineRecordDTO);
        // 1. 根据装填获取DevopsPipelineDTO
        DevopsPipelineDTO devopsPipelineDTO = devopsCdPipelineRecordService.createCDWorkFlowDTO(cdPipelineRecordId, true);
        // 2.更新状态
        DevopsCdStageRecordDTO cdStageRecordDTO = devopsCdStageRecordMapper.queryFailedOrCancelStage(cdPipelineRecordId);
        DevopsCdJobRecordDTO cdJobRecordDTO = devopsCdJobRecordMapper.queryFailedOrCancelJob(cdStageRecordDTO.getId());
        devopsCdStageRecordService.updateStatusById(cdJobRecordDTO.getId(), PipelineStatus.RUNNING.toValue());
        devopsCdJobRecordService.updateStatusById(cdJobRecordDTO.getId(), PipelineStatus.RUNNING.toValue());

        try {
            CustomUserDetails details = DetailsHelper.getUserDetails();
            // 执行流水线
            devopsCdPipelineService.createWorkFlow(projectId, devopsPipelineDTO, details.getUsername(), details.getUserId(), details.getOrganizationId());
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            devopsCdPipelineRecordDTO.setStatus(WorkFlowStatus.FAILED.toValue());
//            devopsCdPipelineRecordDTO.setErrorInfo(e.getMessage());
            devopsCdPipelineRecordService.update(devopsCdPipelineRecordDTO);
        }
    }

    @Override
    public void cancel(Long projectId, Long cdPipelineRecordId, Long gitlabPipelineId, Long gitlabProjectId) {
        if (ObjectUtils.isEmpty(cdPipelineRecordId)) {
            devopsCiPipelineRecordService.cancel(projectId, gitlabPipelineId, gitlabProjectId);
        } else {
            cancelCdPipeline(cdPipelineRecordId);
        }
    }


    @Override
    public void executeNew(Long projectId, Long pipelineId, Long gitlabProjectId, String ref) {
        DevopsCiStageDTO devopsCdStageDTO = new DevopsCiStageDTO();
        devopsCdStageDTO.setCiPipelineId(pipelineId);
        if (devopsCiStageMapper.selectCount(devopsCdStageDTO) == 0) {
            CiCdPipelineVO ciCdPipelineVO = devopsCiPipelineService.queryById(pipelineId);
            AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(ciCdPipelineVO.getAppServiceId());
            DevopsGitlabCommitDTO devopsGitlabCommitDTO = new DevopsGitlabCommitDTO();
            devopsGitlabCommitDTO.setAppServiceId(appServiceDTO.getId());
            devopsGitlabCommitDTO.setRef(ref);
            List<DevopsGitlabCommitDTO> devopsGitlabCommitDTOS = devopsGitlabCommitMapper.select(devopsGitlabCommitDTO);
            if (CollectionUtils.isEmpty(devopsGitlabCommitDTOS)) {
                throw new CommonException("error.no.commit.information.under.the.application.service");
            }
            Date commitDate = devopsGitlabCommitDTOS.get(0).getCommitDate();
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz");
            String sinceDate = simpleDateFormat.format(commitDate);
            List<CommitDTO> commitDTOList = gitlabServiceClientOperator.getCommits(TypeUtil.objToInteger(gitlabProjectId), ref, sinceDate);
            if (CollectionUtils.isEmpty(commitDTOList)) {
                throw new CommonException("error.ref.no.commit");
            }
            devopsCdPipelineService.triggerCdPipeline(appServiceDTO.getToken(), commitDTOList.get(0).getId(), ref, null);
        } else {
            devopsCiPipelineService.executeNew(projectId, pipelineId, gitlabProjectId, ref);
        }
    }

    @Transactional
    public void cancelCdPipeline(Long pipelineRecordId) {
        DevopsCdPipelineRecordDTO pipelineRecordDTO = devopsCdPipelineRecordService.queryById(pipelineRecordId);

        DevopsCdStageRecordDTO cdStageRecordDTO = devopsCdStageRecordMapper.queryPendingAndRunning(pipelineRecordId);
        DevopsCdJobRecordDTO cdJobRecordDTO = devopsCdJobRecordMapper.queryPendingAndRunning(cdStageRecordDTO.getId());
        devopsCdStageRecordService.updateStatusById(cdJobRecordDTO.getId(), PipelineStatus.CANCELED.toValue());
        devopsCdJobRecordService.updateStatusById(cdJobRecordDTO.getId(), PipelineStatus.CANCELED.toValue());
        devopsCdPipelineRecordService.updateStatusById(pipelineRecordId, PipelineStatus.CANCELED.toValue());

        workFlowServiceOperator.stopInstance(pipelineRecordDTO.getProjectId(), pipelineRecordDTO.getBusinessKey());
    }
}