import React, { useEffect } from 'react';
import { inject } from 'mobx-react';
import { observer } from 'mobx-react-lite';
import { injectIntl } from 'react-intl';
import { Header, Choerodon } from '@choerodon/boot';
import { Button, Select, Form } from 'choerodon-ui/pro';
import { CopyToClipboard } from 'react-copy-to-clipboard';
import { Tooltip } from 'choerodon-ui';
import _ from 'lodash';
import handleMapStore from '../main-view/store/handleMapStore';
import { useCodeManagerStore } from '../stores';
import './index.less';

const { Option, OptGroup } = Select;

const CodeManagerToolBar = injectIntl(inject('AppState')(observer((props) => {
  const { appServiceDs, selectAppDs } = useCodeManagerStore();
  useEffect(() => {
    handleRefresh();
  }, [selectAppDs.current]);

  const { name, intl: { formatMessage } } = props;
  const currentApp = _.find(appServiceDs.toData(), ['id', selectAppDs.current.get('appServiceId')]);
  const noRepoUrl = formatMessage({ id: 'repository.noUrl' });
  const getSelfToolBar = () => {
    const obj = handleMapStore[name]
      && handleMapStore[name].getSelfToolBar
      && handleMapStore[name].getSelfToolBar();
    return obj || null;
  };
  /**
   * 点击复制代码成功回调
   * @returns {*|string}
   */
  const handleCopy = () => { Choerodon.prompt('复制成功'); };

  const handleRefresh = () => {
    handleMapStore[name] && handleMapStore[name].refresh();
  };

  const refreshApp = () => {
    appServiceDs.query().then((data) => {
      if (data && data.length && data.length > 0) {
        selectAppDs.current.set('appServiceId', selectAppDs.current.get('appServiceId') || data[0].id);
        handleRefresh();
      }
    });
  };
  return <React.Fragment>
    <Header>
      {getSelfToolBar()}
      <CopyToClipboard
        text={(currentApp && currentApp.repoUrl) || noRepoUrl}
        onCopy={handleCopy}
      >
        <Tooltip title={formatMessage({ id: 'repository.copyUrl' })} placement="bottom">
          <Button icon="content_copy" disabled={!(currentApp && currentApp.repoUrl)}>
            {formatMessage({ id: 'repository.copyUrl' })}
          </Button>
        </Tooltip>
      </CopyToClipboard>
      <Button
        onClick={refreshApp}
        icon="refresh"
      >{formatMessage({ id: 'refresh' })}</Button>
    </Header>
  </React.Fragment>;
})));

export default CodeManagerToolBar;

export const SelectApp = injectIntl(inject('AppState')(observer((props) => {
  const codeManagerStore = useCodeManagerStore();
  const { appServiceDs, selectAppDs } = codeManagerStore;
  const { intl: { formatMessage } } = props;

  return <div style={{ paddingLeft: 24 }}>
    <Form>
      <Select
        className="c7ncd-cm-select"
        label={formatMessage({ id: 'c7ncd.deployment.app-service' })}
        dataSet={selectAppDs}
        notFoundContent={appServiceDs.length === 0 ? formatMessage({ id: 'ist.noApp' }) : '未找到应用服务'}
        searchable
        name="appServiceId"
        clearButton={false}
        disabled={appServiceDs.status !== 'ready' || appServiceDs.length === 0}
      >
        <OptGroup label={formatMessage({ id: 'deploy.app' })} key="app">
          {
          _.map(appServiceDs.toData(), ({ id, code, name: opName }, index) => (
            <Option
              value={id}
              key={index}
            >
              {opName}
            </Option>))
        }
        </OptGroup>
      </Select>
    </Form>
  </div>;
})));