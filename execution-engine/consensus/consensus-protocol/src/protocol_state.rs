use crate::ConsensusContext;
use std::hash::Hash;

pub(crate) trait VertexId {}

pub(crate) trait Vertex<C, Id> {
    fn id(&self) -> Id;

    fn values(&self) -> &[C];
}

pub(crate) trait ProtocolState<Ctx: ConsensusContext> {
    type VertexId;
    type Vertex;

    type Error;

    fn add_vertex(&mut self, v: Self::Vertex) -> Result<Option<Self::VertexId>, Self::Error>;

    fn get_vertex(&self, v: Self::VertexId) -> Result<Option<Self::Vertex>, Self::Error>;
}
