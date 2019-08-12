import React, { Component, Fragment } from 'react';
import { observer, inject } from 'mobx-react';
import { Select, Button } from 'choerodon-ui';
import { injectIntl, FormattedMessage } from 'react-intl';
import _ from 'lodash';
import YamlEditor from '../../../../../../../components/yamlEditor';
import InterceptMask from '../../../../../../../components/interceptMask/InterceptMask';
import LoadingBar from '../../../../../../../components/loadingBar';
import { handlePromptError } from '../../../../../../../utils';

import './index.less';

const { Option } = Select;

@injectIntl
@inject('AppState')
@observer
export default class Upgrade extends Component {
  state = {
    versionId: undefined,
    values: '',
    loading: false,
    submitting: false,
    hasEditorError: false,
    idArr: {},
    versionLoading: false,
    versionOptions: [],
    versions: [],
    versionPageNum: 2,
  };

  componentDidMount() {
    const {
      modal,
      vo: {
        parentId,
        versionId,
      },
    } = this.props;
    const appId = parentId.split('-')[1];

    modal.handleOk(this.handleOk);

    this.handleLoadVersion(appId, '', versionId, true);
  }

  /**
   * 加载版本
   * @param appId
   * @param param 搜索内容
   * @param init 版本id 需要在列表中的版本
   * @param shouldLoadValue 是否需要加载value
   */
  handleLoadVersion = async (appId, param = '', init = '', shouldLoadValue = false) => {
    const {
      AppState: {
        currentMenuType: {
          id: projectId,
        },
      },
      store,
      vo,
    } = this.props;

    this.setState({ versionLoading: true });

    try {
      const data = await store.loadUpVersion({ projectId, appId, page: 1, param, init });
      if (handlePromptError(data)) {
        const { hasNextPage, list } = data;
        const versionOptions = renderVersionOptions(list);

        if (hasNextPage) {
          // 在选项最后置入一个加载更多按钮
          const loadMoreBtn = renderLoadMoreBtn(this.handleLoadMoreVersion);
          versionOptions.push(loadMoreBtn);
        }

        this.setState({
          versionOptions,
          versions: list,
          versionLoading: false,
        });

        if (shouldLoadValue) {
          const { id, parentId } = vo;
          const [envId] = parentId.split('-');

          const newIdArr = {
            appInstanceId: id,
            environmentId: envId,
            appId,
            appVersionId: list[0].id,
          };
          this.setState({ idArr: newIdArr });
          this.handleVersionChange(list[0].id);
        }
      }
    } catch (e) {
      this.setState({ versionLoading: false });
    }
  };

  /**
   * 搜索版本
   */
  handleVersionSearch = _.debounce((value) => {
    const {
      idArr,
    } = this.state;
    this.setState({ versionSearchParam: value, versionPageNum: 2 });
    this.handleLoadVersion(idArr.appId, value, '');
  }, 500);

  /**
   * 点击加载更多
   * @param e
   */
  handleLoadMoreVersion = async (e) => {
    e.stopPropagation();

    const {
      AppState: {
        currentMenuType: {
          projectId,
        },
      },
      store,
    } = this.props;

    const {
      versionId,
      versionOptions,
      versions,
      idArr: {
        appId,
      },
      versionPageNum,
      versionSearchParam,
    } = this.state;

    this.setState({ versionLoading: true });

    const data = await store.loadUpVersion({
      projectId,
      appId,
      page: versionPageNum,
      param: versionSearchParam,
    })
      .catch(() => {
        this.setState({ versionLoading: false });
      });

    if (handlePromptError(data)) {
      const { hasNextPage, list } = data;

      const moreVersion = _.filter(list, ({ id }) => id !== versionId);
      const options = renderVersionOptions(moreVersion);

      /**
       * 触发此事件说明初次渲染的选项versionOptions中的最后一个肯定是 “展开更多” 按钮
       * 可以先将该按钮使用 initial 方法从原来去除
       * 如果当前页页码仍然小于总页数，再讲该按钮放回到新的选项的最后
       */
      const newVersionOpt = _.concat(
        _.initial(versionOptions),
        options,
        hasNextPage ? _.last(versionOptions) : [],
      );
      const newVersions = _.concat(versions, moreVersion);

      this.setState({
        versionOptions: newVersionOpt,
        versions: newVersions,
        versionLoading: false,
        versionPageNum: versionPageNum + 1,
      });
    }
  };

  handleNextStepEnable = (flag) => {
    const { modal } = this.props;
    this.setState({ hasEditorError: flag });
    modal.update({ okProps: { disabled: flag } });
  };

  handleChangeValue = values => this.setState({ values });

  /**
   * 修改配置升级实例
   */
  handleOk = async () => {
    if (this.state.hasEditorError) return false;

    const {
      store,
      appInstanceId,
      onClose,
      AppState: {
        currentMenuType: { projectId },
      },
    } = this.props;

    const {
      values,
      versionId,
      idArr,
      versions,
    } = this.state;
    const verId = versionId || versions[0].id;
    const { id, yaml } = store.getUpgradeValue || {};

    const data = {
      ...idArr,
      values: values || yaml || '',
      valueId: id,
      appInstanceId,
      appVersionId: verId,
      type: 'update',
    };

    this.setState({ submitting: true });
    const res = await store.upgrade(projectId, data)
      .catch((e) => {
        this.setState({ submitting: false });
        onClose(true);
        Choerodon.handleResponseError(e);
      });

    if (res && res.failed) {
      Choerodon.prompt(res.message);
    }

    this.setState({ submitting: false });
  };

  /**
   * 切换实例版本，加载该版本下的配置内容
   * @param id
   */
  handleVersionChange = (id) => {
    const {
      store,
      AppState: {
        currentMenuType: { projectId },
      },
      vo: { id: appInstanceId },
    } = this.props;
    this.setState({ versionId: id, values: null, loading: true });
    store.setUpgradeValue({});
    store.loadValue(projectId, appInstanceId, id).then(() => {
      this.setState({ loading: false });
    });
  };

  render() {
    const {
      intl: { formatMessage },
      store,
      intlPrefix,
      prefixCls,
    } = this.props;
    const {
      values,
      submitting,
      loading,
      versionId,
      versionOptions,
      versions,
      versionLoading,
    } = this.state;

    const { name, yaml } = store.getUpgradeValue;

    return (
      <Fragment>
        <Select
          filter
          className={`${prefixCls}-version-select`}
          label={formatMessage({ id: `${intlPrefix}.modal.version` })}
          notFoundContent={formatMessage({ id: `${intlPrefix}.modal.version.empty` })}
          loading={versionLoading}
          filterOption={false}
          onSearch={this.handleVersionSearch}
          onChange={this.handleVersionChange}
          value={versionId || (versions.length ? versions[0].id : undefined)}
        >
          {versionOptions}
        </Select>
        <div className={`${prefixCls}-configValue-text`}>
          <span>{formatMessage({ id: `${intlPrefix}.modal.config` })}：</span>
          <span className={`${prefixCls}-configValue-name`}>
            {name || formatMessage({ id: `${intlPrefix}.modal.config.empty` })}
          </span>
        </div>
        <div className="c7n-config-section">
          <YamlEditor
            readOnly={false}
            value={values || yaml || ''}
            originValue={yaml}
            handleEnableNext={this.handleNextStepEnable}
            onValueChange={this.handleChangeValue}
          />
        </div>
        <LoadingBar display={loading} />
        <InterceptMask visible={submitting} />
      </Fragment>
    );
  }
}

/**
 * 生成版本选项
 * @param versions
 */
function renderVersionOptions(versions) {
  return _.map(versions, ({ id, version }) => <Option key={id} value={id}>{version}</Option>);
}

function renderLoadMoreBtn(handler) {
  return <Option
    disabled
    className="c7ncd-more-btn-wrap"
    key="btn_load_more"
  >
    <Button
      type="default"
      className="c7ncd-more-btn"
      onClick={handler}
    >
      <FormattedMessage id="loadMore" />
    </Button>
  </Option>;
}
