import React, { Fragment, useMemo } from 'react';
import PropTypes from 'prop-types';
import { injectIntl } from 'react-intl';
import { Action } from '@choerodon/boot';
import PodCircle from '../../components/pod-circle';
import { useSidebarStore } from '../stores';

function InstanceItem({
  istId,
  name,
  podColor,
  running,
  unlink,
  intlPrefix,
  intl: { formatMessage },
}) {
  const { treeDs } = useSidebarStore();

  const podData = useMemo(() => {
    const {
      RUNNING_COLOR,
      PADDING_COLOR,
    } = podColor;

    return [{
      name: 'running',
      value: running,
      stroke: RUNNING_COLOR,
    }, {
      name: 'unlink',
      value: unlink,
      stroke: PADDING_COLOR,
    }];
  }, [podColor, running, unlink]);

  function freshMenu() {
    treeDs.query();
  }

  const getSuffix = useMemo(() => {
    const actionData = [{
      service: [],
      text: formatMessage({ id: `${intlPrefix}.instance.action.stop` }),
      action: freshMenu,
    }, {
      service: [],
      text: formatMessage({ id: `${intlPrefix}.instance.action.delete` }),
      action: freshMenu,
    }];
    return <Action placement="bottomRight" data={actionData} />;
  }, []);

  return <Fragment>
    <PodCircle
      size="small"
      dataSource={podData}
    />
    {name}
    {getSuffix}
  </Fragment>;
}

InstanceItem.propTypes = {
  istId: PropTypes.number,
  name: PropTypes.any,
  podColor: PropTypes.shape({}),
  running: PropTypes.number,
  unlink: PropTypes.number,
};

export default injectIntl(InstanceItem);
