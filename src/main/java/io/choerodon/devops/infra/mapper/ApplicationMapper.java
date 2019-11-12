package io.choerodon.devops.infra.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import io.choerodon.devops.infra.dataobject.ApplicationDO;
import io.choerodon.mybatis.common.Mapper;

/**
 * Created by younger on 2018/3/28.
 */
public interface ApplicationMapper extends Mapper<ApplicationDO> {
    List<ApplicationDO> list(@Param("projectId") Long projectId,
                             @Param("isActive") Boolean isActive,
                             @Param("hasVersion") Boolean hasVersion,
                             @Param("appMarket") Boolean appMarket,
                             @Param("type") String type,
                             @Param("searchParam") Map<String, Object> searchParam,
                             @Param("param") String param,
                             @Param("index") String index);

    List<ApplicationDO> listCodeRepository(@Param("projectId") Long projectId,
                                           @Param("searchParam") Map<String, Object> searchParam,
                                           @Param("param") String param,
                                           @Param("isProjectOwner") Boolean isProjectOwner,
                                           @Param("userId") Long userId);

    List<ApplicationDO> listByEnvId(@Param("projectId") Long projectId,
                                    @Param("envId") Long envId,
                                    @Param("appId") Long appId,
                                    @Param("status") String status);

    List<ApplicationDO> listByActiveAndPubAndVersion(@Param("projectId") Long projectId,
                                                     @Param("active") Boolean active,
                                                     @Param("searchParam") Map<String, Object> searchParam,
                                                     @Param("param") String param);

    ApplicationDO queryByToken(@Param("token") String token);

    List<ApplicationDO> listActive(@Param("projectId") Long projectId);

    List<ApplicationDO> listAll(@Param("projectId") Long projectId);

    Integer checkAppCanDisable(@Param("applicationId") Long applicationId);

    List<ApplicationDO> listByCode(@Param("code") String code);

    List<ApplicationDO> listByGitLabProjectIds(@Param("gitlabProjectIds") List<Long> gitlabProjectIds);

    void updateAppToSuccess(@Param("appId") Long appId);

    void updateSql(@Param("appId") Long appId,
                   @Param("token") String token,
                   @Param("gitlabProjectId") Integer gitlabProjectId,
                   @Param("hookId") Long hookId,
                   @Param("isSynchro") Boolean isSynchro);

    void updateAppHarborConfig(@Param("projectId") Long projectId, @Param("newConfigId") Long newConfigId, @Param("oldConfigId") Long oldConfigId, @Param("harborPrivate") boolean harborPrivate);

    /**
     * 根据gitlabGroupId和iamUserId获取
     * 在整个项目组用权限的应用对应的gitlabProjectId
     *
     * @return
     */
    List<Long> listGitlabProjectIdByAppPermission(@Param("gitlabGroupId") Long gitlabGroupId, @Param("iamUserId") Long iamUserId);
}