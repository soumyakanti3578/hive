<?php
namespace metastore;

/**
 * Autogenerated by Thrift Compiler (0.16.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
use Thrift\Base\TBase;
use Thrift\Type\TType;
use Thrift\Type\TMessageType;
use Thrift\Exception\TException;
use Thrift\Exception\TProtocolException;
use Thrift\Protocol\TProtocol;
use Thrift\Protocol\TBinaryProtocolAccelerated;
use Thrift\Exception\TApplicationException;

class ExtendedTableInfo
{
    static public $isValidate = false;

    static public $_TSPEC = array(
        1 => array(
            'var' => 'tblName',
            'isRequired' => true,
            'type' => TType::STRING,
        ),
        2 => array(
            'var' => 'accessType',
            'isRequired' => false,
            'type' => TType::I32,
        ),
        3 => array(
            'var' => 'requiredReadCapabilities',
            'isRequired' => false,
            'type' => TType::LST,
            'etype' => TType::STRING,
            'elem' => array(
                'type' => TType::STRING,
                ),
        ),
        4 => array(
            'var' => 'requiredWriteCapabilities',
            'isRequired' => false,
            'type' => TType::LST,
            'etype' => TType::STRING,
            'elem' => array(
                'type' => TType::STRING,
                ),
        ),
    );

    /**
     * @var string
     */
    public $tblName = null;
    /**
     * @var int
     */
    public $accessType = null;
    /**
     * @var string[]
     */
    public $requiredReadCapabilities = null;
    /**
     * @var string[]
     */
    public $requiredWriteCapabilities = null;

    public function __construct($vals = null)
    {
        if (is_array($vals)) {
            if (isset($vals['tblName'])) {
                $this->tblName = $vals['tblName'];
            }
            if (isset($vals['accessType'])) {
                $this->accessType = $vals['accessType'];
            }
            if (isset($vals['requiredReadCapabilities'])) {
                $this->requiredReadCapabilities = $vals['requiredReadCapabilities'];
            }
            if (isset($vals['requiredWriteCapabilities'])) {
                $this->requiredWriteCapabilities = $vals['requiredWriteCapabilities'];
            }
        }
    }

    public function getName()
    {
        return 'ExtendedTableInfo';
    }


    public function read($input)
    {
        $xfer = 0;
        $fname = null;
        $ftype = 0;
        $fid = 0;
        $xfer += $input->readStructBegin($fname);
        while (true) {
            $xfer += $input->readFieldBegin($fname, $ftype, $fid);
            if ($ftype == TType::STOP) {
                break;
            }
            switch ($fid) {
                case 1:
                    if ($ftype == TType::STRING) {
                        $xfer += $input->readString($this->tblName);
                    } else {
                        $xfer += $input->skip($ftype);
                    }
                    break;
                case 2:
                    if ($ftype == TType::I32) {
                        $xfer += $input->readI32($this->accessType);
                    } else {
                        $xfer += $input->skip($ftype);
                    }
                    break;
                case 3:
                    if ($ftype == TType::LST) {
                        $this->requiredReadCapabilities = array();
                        $_size1099 = 0;
                        $_etype1102 = 0;
                        $xfer += $input->readListBegin($_etype1102, $_size1099);
                        for ($_i1103 = 0; $_i1103 < $_size1099; ++$_i1103) {
                            $elem1104 = null;
                            $xfer += $input->readString($elem1104);
                            $this->requiredReadCapabilities []= $elem1104;
                        }
                        $xfer += $input->readListEnd();
                    } else {
                        $xfer += $input->skip($ftype);
                    }
                    break;
                case 4:
                    if ($ftype == TType::LST) {
                        $this->requiredWriteCapabilities = array();
                        $_size1105 = 0;
                        $_etype1108 = 0;
                        $xfer += $input->readListBegin($_etype1108, $_size1105);
                        for ($_i1109 = 0; $_i1109 < $_size1105; ++$_i1109) {
                            $elem1110 = null;
                            $xfer += $input->readString($elem1110);
                            $this->requiredWriteCapabilities []= $elem1110;
                        }
                        $xfer += $input->readListEnd();
                    } else {
                        $xfer += $input->skip($ftype);
                    }
                    break;
                default:
                    $xfer += $input->skip($ftype);
                    break;
            }
            $xfer += $input->readFieldEnd();
        }
        $xfer += $input->readStructEnd();
        return $xfer;
    }

    public function write($output)
    {
        $xfer = 0;
        $xfer += $output->writeStructBegin('ExtendedTableInfo');
        if ($this->tblName !== null) {
            $xfer += $output->writeFieldBegin('tblName', TType::STRING, 1);
            $xfer += $output->writeString($this->tblName);
            $xfer += $output->writeFieldEnd();
        }
        if ($this->accessType !== null) {
            $xfer += $output->writeFieldBegin('accessType', TType::I32, 2);
            $xfer += $output->writeI32($this->accessType);
            $xfer += $output->writeFieldEnd();
        }
        if ($this->requiredReadCapabilities !== null) {
            if (!is_array($this->requiredReadCapabilities)) {
                throw new TProtocolException('Bad type in structure.', TProtocolException::INVALID_DATA);
            }
            $xfer += $output->writeFieldBegin('requiredReadCapabilities', TType::LST, 3);
            $output->writeListBegin(TType::STRING, count($this->requiredReadCapabilities));
            foreach ($this->requiredReadCapabilities as $iter1111) {
                $xfer += $output->writeString($iter1111);
            }
            $output->writeListEnd();
            $xfer += $output->writeFieldEnd();
        }
        if ($this->requiredWriteCapabilities !== null) {
            if (!is_array($this->requiredWriteCapabilities)) {
                throw new TProtocolException('Bad type in structure.', TProtocolException::INVALID_DATA);
            }
            $xfer += $output->writeFieldBegin('requiredWriteCapabilities', TType::LST, 4);
            $output->writeListBegin(TType::STRING, count($this->requiredWriteCapabilities));
            foreach ($this->requiredWriteCapabilities as $iter1112) {
                $xfer += $output->writeString($iter1112);
            }
            $output->writeListEnd();
            $xfer += $output->writeFieldEnd();
        }
        $xfer += $output->writeFieldStop();
        $xfer += $output->writeStructEnd();
        return $xfer;
    }
}
