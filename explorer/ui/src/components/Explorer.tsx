import React from 'react';
import { observer } from 'mobx-react';
import CasperContainer from '../containers/CasperContainer';
import { RefreshableComponent } from './Utils';
import { BlockDAG } from './BlockDAG';
import DataTable from './DataTable';
import { encodeBase16 } from '../lib/Conversions';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import $ from 'jquery';

interface Props {
  casper: CasperContainer;
}

const DefaultDepth = 10;

/** Show the tips of the DAG. */
@observer
export default class Explorer extends RefreshableComponent<Props, {}> {
  async refresh() {
    this.props.casper.refreshBlockDag(DefaultDepth);
  }

  render() {
    return (
      <div>
        <BlockDAG
          title="Recent Block DAG"
          blocks={this.props.casper.blocks}
          refresh={() => this.refresh()}
          footerMessage="Select a block to see its details."
          onSelected={block => (this.props.casper.selectedBlock = block)}
          selected={this.props.casper.selectedBlock}
          width="100%"
          height="600"
        />
        {this.props.casper.selectedBlock && (
          <BlockDetails
            block={this.props.casper.selectedBlock}
            blocks={this.props.casper.blocks!}
            onSelect={blockHash => {
              this.props.casper.selectedBlock = this.props.casper.blocks!.find(
                x =>
                  encodeBase16(x.getSummary()!.getBlockHash_asU8()) ===
                  blockHash
              );
            }}
          />
        )}
      </div>
    );
  }
}

class BlockDetails extends React.Component<
  {
    block: BlockInfo;
    blocks: BlockInfo[];
    onSelect: (blockHash: string) => void;
  },
  {}
> {
  ref: HTMLElement | null = null;

  render() {
    let { block } = this.props;
    let summary = block.getSummary()!;
    let header = summary.getHeader()!;
    let id = encodeBase16(summary.getBlockHash_asU8());
    let idB64 = summary.getBlockHash_asB64();
    let attrs: Array<[string, any]> = [
      ['Block hash', id],
      ['Timestamp', new Date(header.getTimestamp()).toISOString()],
      ['Rank', header.getRank()],
      ['Validator', encodeBase16(header.getValidatorPublicKey_asU8())],
      ['Validator block number', header.getValidatorBlockSeqNum()],
      [
        'Parents',
        <ul>
          {header.getParentHashesList_asU8().map((x, idx) => (
            <li key={idx}>
              <BlockLink blockHash={x} onClick={this.props.onSelect} />
            </li>
          ))}
        </ul>
      ],
      [
        'Children',
        <ul>
          {this.props.blocks
            .filter(
              b =>
                b
                  .getSummary()!
                  .getHeader()!
                  .getParentHashesList_asB64()
                  .findIndex(p => p === idB64) > -1
            )
            .map((b, idx) => (
              <li key={idx}>
                <BlockLink
                  blockHash={b.getSummary()!.getBlockHash_asU8()}
                  onClick={this.props.onSelect}
                />
              </li>
            ))}
        </ul>
      ],
      ['Deploy count', header.getDeployCount()],
      ['Fault tolerance', block.getStatus()!.getFaultTolerance()]
    ];
    return (
      <div
        ref={x => {
          this.ref = x;
        }}
      >
        <DataTable
          title={`Block ${id}`}
          headers={[]}
          rows={attrs}
          renderRow={(attr, idx) => (
            <tr key={idx}>
              <th>{attr[0]}</th>
              <td>{attr[1]}</td>
            </tr>
          )}
        />
      </div>
    );
  }

  componentDidMount() {
    this.scrollToBlockDetails();
  }

  componentDidUpdate() {
    this.scrollToBlockDetails();
  }

  scrollToBlockDetails() {
    let container = $(this.ref!);
    let offset = container.offset()!;
    let height = container.height()!;
    $('html, body')
      .stop()
      .animate(
        {
          scrollTop: offset.top + height
        },
        1000
      );
  }
}

const BlockLink = (props: {
  blockHash: ByteArray;
  onClick: (blockHashBase16: string) => void;
}) => {
  let id = encodeBase16(props.blockHash);
  return (
    <button className="link" onClick={() => props.onClick(id)}>
      {id}
    </button>
  );
};
